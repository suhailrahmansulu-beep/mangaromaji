"""Romaji Overlay for Windows.

Press Ctrl+Alt+R: it grabs one screenshot, reads the Japanese with Windows' built-in OCR,
and paints romaji over the text. Press again (or wait) to clear it.

It only takes a screenshot and shows its own transparent window. It never touches
the game's files or memory.
"""
import asyncio
import ctypes
import io
import queue
import sys
import threading
import tkinter as tk
import tkinter.font as tkfont
from ctypes import wintypes
from tkinter import messagebox, scrolledtext

from PIL import ImageGrab

from romaji import JP, Romanizer, clean_ocr

HOTKEY_TEXT = "Ctrl+Alt+R"
KEY = "#010101"   # transparent colour key

user32 = ctypes.windll.user32
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)   # real pixels, so boxes line up
except Exception:
    pass

SW = user32.GetSystemMetrics(0)
SH = user32.GetSystemMetrics(1)


# ---------------------------------------------------------------- OCR (Windows built-in)
class Ocr:
    def __init__(self):
        from winsdk.windows.globalization import Language
        from winsdk.windows.media.ocr import OcrEngine
        self.engine = OcrEngine.try_create_from_language(Language("ja"))

    @property
    def ok(self):
        return self.engine is not None

    async def _read(self, bmp_bytes):
        from winsdk.windows.graphics.imaging import BitmapDecoder
        from winsdk.windows.storage.streams import DataWriter, InMemoryRandomAccessStream
        stream = InMemoryRandomAccessStream()
        writer = DataWriter(stream)
        writer.write_bytes(bmp_bytes)
        await writer.store_async()
        writer.detach_stream()
        stream.seek(0)
        decoder = await BitmapDecoder.create_async(stream)
        bitmap = await decoder.get_software_bitmap_async()
        result = await self.engine.recognize_async(bitmap)
        lines = []
        for line in result.lines:
            words = list(line.words)
            if not words:
                continue
            x0 = min(w.bounding_rect.x for w in words)
            y0 = min(w.bounding_rect.y for w in words)
            x1 = max(w.bounding_rect.x + w.bounding_rect.width for w in words)
            y1 = max(w.bounding_rect.y + w.bounding_rect.height for w in words)
            lines.append((x0, y0, x1 - x0, y1 - y0, clean_ocr(" ".join(w.text for w in words))))
        return lines

    def read(self, image):
        buf = io.BytesIO()
        image.save(buf, "BMP")
        return asyncio.run(self._read(buf.getvalue()))


def group_lines(lines):
    """Join neighbouring lines of one message into one block."""
    lines = sorted(lines, key=lambda l: (l[1], l[0]))
    groups = []
    for x, y, w, h, t in lines:
        for g in groups:
            gx, gy, gw, gh, gt = g
            gap = y - (gy + gh)
            if -h * 0.5 <= gap <= h * 0.9 and abs(x - gx) <= h * 2:
                nx, ny = min(gx, x), gy
                nr, nb = max(gx + gw, x + w), max(gy + gh, y + h)
                g[:] = [nx, ny, nr - nx, nb - ny, gt + t]
                break
        else:
            groups.append([x, y, w, h, t])
    return groups


# ---------------------------------------------------------------- hotkey (no hooks, no injection)
def hotkey_thread(q):
    MOD_ALT, MOD_CONTROL, MOD_NOREPEAT = 0x0001, 0x0002, 0x4000
    if not user32.RegisterHotKey(None, 1, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, 0x52):  # R
        q.put(("error", f"{HOTKEY_TEXT} is already used by another program."))
        return
    msg = wintypes.MSG()
    while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) != 0:
        if msg.message == 0x0312:   # WM_HOTKEY
            q.put(("hotkey",))


# ---------------------------------------------------------------- overlay window
class Overlay:
    def __init__(self, root):
        self.win = tk.Toplevel(root)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True)
        self.win.attributes("-transparentcolor", KEY)
        self.win.geometry(f"{SW}x{SH}+0+0")
        self.canvas = tk.Canvas(self.win, bg=KEY, highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.win.update_idletasks()
        self._style()
        self.win.withdraw()
        self.visible = False

    def _hwnd(self):
        return user32.GetParent(self.win.winfo_id()) or self.win.winfo_id()

    def _style(self):
        # click-through + never takes focus, so the game keeps keyboard and mouse
        GWL_EXSTYLE = -20
        LAYERED, TRANSPARENT, TOOLWINDOW, NOACTIVATE = 0x80000, 0x20, 0x80, 0x08000000
        h = self._hwnd()
        st = user32.GetWindowLongW(h, GWL_EXSTYLE)
        user32.SetWindowLongW(h, GWL_EXSTYLE, st | LAYERED | TRANSPARENT | TOOLWINDOW | NOACTIVATE)

    def show(self, items):
        self.canvas.delete("all")
        for (x, y, w, h, text) in items:
            self._bubble(x, y, w, h, text)
        self.win.deiconify()
        self._style()
        # topmost, no activation
        user32.SetWindowPos(self._hwnd(), -1, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 | 0x0040)
        self.visible = True

    def hide(self):
        self.canvas.delete("all")
        self.win.withdraw()
        self.visible = False

    def _bubble(self, x, y, w, h, text):
        px = max(11, min(22, int(h * 0.62)))
        font = tkfont.Font(family="Segoe UI", size=-px, weight="bold")
        pad = 6
        text_w = font.measure(text)
        width = int(max(w, min(text_w + pad * 2, SW * 0.5)))
        lines = max(1, -(-text_w // max(1, width - pad * 2)))
        height = int(lines * font.metrics("linespace") * 1.08 + pad * 2)
        height = max(height, int(h))
        x = max(0, min(x, SW - width))
        y = max(0, min(y, SH - height))
        self.canvas.create_rectangle(x, y, x + width, y + height, fill="#FFFFFF", outline="#B8BEC9")
        self.canvas.create_text(x + pad, y + pad, anchor="nw", text=text, font=font,
                                fill="#101318", width=width - pad * 2)


# ---------------------------------------------------------------- app
class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Romaji Overlay")
        self.root.geometry("440x420")
        self.root.configure(bg="#0E1117")
        self.q = queue.Queue()
        self.busy = False
        self.hide_job = None

        tk.Label(self.root, text="Romaji Overlay", fg="#EEF1F6", bg="#0E1117",
                 font=("Segoe UI", 18, "bold")).pack(anchor="w", padx=16, pady=(14, 0))
        self.status = tk.Label(self.root, text="Starting...", fg="#8C96A8", bg="#0E1117",
                               font=("Segoe UI", 10), justify="left", wraplength=400)
        self.status.pack(anchor="w", padx=16, pady=(2, 8))

        opts = tk.Frame(self.root, bg="#0E1117")
        opts.pack(anchor="w", padx=16)
        self.macrons = tk.BooleanVar(value=True)
        tk.Checkbutton(opts, text="Long vowel marks (ō ū)", variable=self.macrons, fg="#EEF1F6",
                       bg="#0E1117", selectcolor="#171C26", activebackground="#0E1117",
                       activeforeground="#EEF1F6", command=self._opts).pack(side="left")
        tk.Label(opts, text="   Hide after", fg="#EEF1F6", bg="#0E1117").pack(side="left")
        self.secs = tk.IntVar(value=12)
        tk.Spinbox(opts, from_=3, to=60, width=4, textvariable=self.secs).pack(side="left", padx=4)
        tk.Label(opts, text="s", fg="#EEF1F6", bg="#0E1117").pack(side="left")

        tk.Label(self.root, text="History", fg="#8C96A8", bg="#0E1117",
                 font=("Segoe UI", 9, "bold")).pack(anchor="w", padx=16, pady=(12, 2))
        self.log = scrolledtext.ScrolledText(self.root, height=12, bg="#171C26", fg="#EEF1F6",
                                             insertbackground="#EEF1F6", relief="flat",
                                             font=("Segoe UI", 10), wrap="word")
        self.log.pack(fill="both", expand=True, padx=16, pady=(0, 14))

        self.overlay = Overlay(self.root)
        self.ocr = None
        self.rom = None
        self.root.after(100, self._init_engines)
        self.root.after(80, self._pump)
        self.root.protocol("WM_DELETE_WINDOW", self._quit)

    def _set(self, text):
        self.status.config(text=text)

    def _opts(self):
        if self.rom:
            self.rom.macrons = self.macrons.get()

    def _init_engines(self):
        try:
            self.ocr = Ocr()
        except Exception as e:
            messagebox.showerror("Romaji Overlay", f"Windows OCR could not start:\n{e}")
            self._set("Windows OCR could not start.")
            return
        if not self.ocr.ok:
            messagebox.showerror(
                "Japanese OCR is missing",
                "Windows needs its Japanese text-recognition pack (one-time, free):\n\n"
                "Settings > Time & language > Language & region > Add a language > Japanese,\n"
                "then make sure \"Optical character recognition\" is ticked. Restart this app after.")
            self._set("Japanese OCR pack not installed. See the message that popped up.")
            return
        self.rom = Romanizer(macrons=self.macrons.get())
        threading.Thread(target=hotkey_thread, args=(self.q,), daemon=True).start()
        self._set(f"Ready. Press {HOTKEY_TEXT} in your game to translate. Press again to clear.\n"
                  "Tip: use Windowed or Borderless mode so the overlay can show.")

    def _pump(self):
        try:
            while True:
                msg = self.q.get_nowait()
                kind = msg[0]
                if kind == "hotkey":
                    self._on_hotkey()
                elif kind == "result":
                    self._on_result(msg[1])
                elif kind == "error":
                    self.busy = False
                    self._set(msg[1])
        except queue.Empty:
            pass
        self.root.after(80, self._pump)

    def _on_hotkey(self):
        if not (self.ocr and self.ocr.ok and self.rom) or self.busy:
            return
        if self.overlay.visible:
            self._clear()
            return
        self.busy = True
        self._set("Reading the screen...")
        image = ImageGrab.grab(bbox=(0, 0, SW, SH)).convert("RGB")   # overlay is hidden now
        threading.Thread(target=self._work, args=(image,), daemon=True).start()

    def _work(self, image):
        try:
            blocks = group_lines(self.ocr.read(image))
            items = []
            for x, y, w, h, text in blocks:
                if not JP.search(text):
                    continue
                roma = self.rom.convert(text)
                if roma.strip():
                    items.append((x, y, w, h, roma))
            self.q.put(("result", items))
        except Exception as e:
            self.q.put(("error", f"Could not read the screen: {e}"))

    def _on_result(self, items):
        self.busy = False
        if not items:
            self._set("No Japanese found on the screen.")
            return
        self.overlay.show(items)
        self._set(f"Showing {len(items)} text block(s). Press {HOTKEY_TEXT} to clear.")
        for *_, t in items:
            self.log.insert("end", t + "\n\n")
        self.log.see("end")
        if self.hide_job:
            self.root.after_cancel(self.hide_job)
        self.hide_job = self.root.after(max(3, self.secs.get()) * 1000, self._clear)

    def _clear(self):
        if self.hide_job:
            self.root.after_cancel(self.hide_job)
            self.hide_job = None
        self.overlay.hide()
        self._set(f"Ready. Press {HOTKEY_TEXT} to translate.")

    def _quit(self):
        self.root.destroy()
        sys.exit(0)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().run()
