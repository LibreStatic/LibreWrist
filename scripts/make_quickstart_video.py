#!/usr/bin/env python3
"""Builds the README Quick start video (docs/media/quick-start.mp4) and its inline GIF from the
walkthrough screenshots in docs/images/walkthrough. Needs ImageMagick 7 and ffmpeg with libx264.

Environment: WORK_DIR (scratch frames, default ~/.cache/shm-interop), QUICKSTART_FONT and
QUICKSTART_FONT_BOLD (TrueType files; default Noto Sans)."""
import os, subprocess

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMG = f"{REPO}/docs/images/walkthrough"
OUT = f"{REPO}/docs/media"
WORK = os.path.join(os.environ.get("WORK_DIR", os.path.expanduser("~/.cache/shm-interop")), "quickstart")
REG = os.environ.get("QUICKSTART_FONT", "/usr/share/fonts/noto/NotoSans-Regular.ttf")
BOLD = os.environ.get("QUICKSTART_FONT_BOLD", "/usr/share/fonts/noto/NotoSans-Bold.ttf")
BG, INK, MUTED, ACCENT = "#EEF0FF", "#1B1B21", "#46464F", "#4355B9"
W, H = 1920, 1080

# (image or None, step label, title, caption, seconds)
SLIDES = [
    (None, "", "LibreWrist Quick start",
     "ECG and blood pressure from your Galaxy Watch, on a phone that is not a Samsung. "
     "Unattended mode does most of the work, all on the phone, no PC.", 4.5),
    ("01-welcome", "Before you start", "Open LibreWrist",
     "Install the APK from Releases. Unattended mode is on by default: leave it on and tap Get started.", 4.5),
    ("01a-apps-notice", "Before you start", "The apps come from you",
     "LibreWrist never downloads the Samsung apps itself. Unattended mode waits at that step until you bring them.", 4.5),
    ("01b-phone-adb", "Before you start", "Connect this phone",
     "Unattended mode works through this phone's Wireless debugging, so Android does not ask about each install.", 4.5),
    ("14b-phone-build-number", "Before you start", "Turn on Developer options",
     "Settings, About phone: tap Build number 7 times.", 3.5),
    ("14e-phone-allow-network", "Before you start", "Turn on Wireless debugging",
     "Developer options, Wireless debugging: turn it on and tap Allow.", 3.5),
    ("14g-phone-pairing-code", "Before you start", "Get a pairing code",
     "Tap Pair device with pairing code. Keep the code on screen.", 3.5),
    ("14i-phone-notification-code", "Before you start", "Give the code to LibreWrist",
     "Pull down the notifications, tap Enter code, type the 6 digits and send. This is needed only once.", 4.5),
    ("03-watch-profile-auto", "Step 1 of 9", "Steps move on by themselves",
     "Your watch is checked for you, and after 2 seconds the step continues. Touch the screen to take over.", 5),
    ("04-wearable-pairing-auto", "Step 2 of 9", "Galaxy Wearable checked",
     "Pair the watch with Galaxy Wearable as usual. Once all three checks are green, it goes on.", 4),
    ("05-watch-adb-pairing", "Step 3 of 9", "Connect to the watch",
     "Your part. Same Wi-Fi on both. On the watch, tap Software version until developer mode turns on.", 4.5),
    ("06-wireless-debugging-guide", "Step 3 of 9", "Wireless debugging on the watch",
     "Developer options, Wireless debugging: turn it on and tap Allow.", 3.5),
    ("08-pairing-code", "Step 3 of 9", "Type the watch's code",
     "Tap Pair new device on the watch, type its 6-digit code and tap Pair.", 4),
    ("09-official-apps", "Step 4 of 9", "Bring the official apps",
     "Your part. Download each Samsung app yourself; the guided browser shows which file to pick. Every file is checked.", 4.5),
    (None, "", "From here on, hands off",
     "Patching, signing, installing on both devices and the checks run by themselves. "
     "Keep the watch unlocked and nearby.", 4.5),
    ("11-patches-auto", "Step 5 of 9", "Recommended patches", "The build starts with the recommended patches.", 3),
    ("13-built-auto", "Step 6 of 9", "Built and signed",
     "With a key created on, and kept on, your phone. Back it up later: updates need it.", 3.5),
    ("14-install-phone", "Step 7 of 9", "Installed on the phone",
     "Over Wireless debugging: no Android prompts, and the permissions are granted for you.", 3.5),
    ("16-install-watch", "Step 8 of 9", "Installed on the watch", "Over Wi-Fi, with its permissions granted for you.", 3),
    ("17-developer-mode-auto", "Step 8 of 9", "Health Platform developer mode",
     "Turned on for you. Keep the watch unlocked and awake.", 3.5),
    ("18-disable-stock-auto", "Step 8 of 9", "Stock app turned off",
     "The stock Health Monitor is turned off, not uninstalled.", 3),
    ("19-self-test", "Step 9 of 9", "Self-test", "Both devices and the link between them are checked.", 3),
    ("20-all-set-auto", "", "You're all set", "Home shows the state of your apps from now on.", 3),
    ("22-unattended-off", "", "Prefer to do it by hand?",
     "Turn off Unattended mode on the first screen. Every step then waits for you, and Android asks before each install.", 4.5),
    (None, "", "Now try it",
     "On the watch, open Samsung Health Monitor and take an ECG. "
     "A minute later it shows up in the app on your phone.", 4.5),
]
FADE = 0.5


def run(*cmd):
    subprocess.run(cmd, check=True)


def bg_of(png):
    """Colour of the screenshot's top-left corner (the status bar background)."""
    return subprocess.run(["magick", png, "-format", "%[pixel:p{2,2}]", "info:"], check=True,
                          capture_output=True, text=True).stdout.strip()


def slide_png(i, s):
    img, label, title, caption, _ = s
    out = f"{WORK}/s{i:02d}.png"
    text_w = 1000 if img else 1500
    x0 = 150 if img else 210
    cmd = ["magick", "-size", f"{W}x{H}", f"xc:{BG}"]
    y = 330 if img else 360
    if label:
        cmd += ["-font", BOLD, "-pointsize", "34", "-fill", ACCENT, "-annotate", f"+{x0}+{y}", label.upper()]
        y += 40
    # Title and caption are wrapped by caption: into transparent boxes and composited.
    title_png = f"{WORK}/t{i:02d}.png"
    run("magick", "-size", f"{text_w}x", "-background", "none", "-font", BOLD, "-pointsize", "76" if img else "96",
        "-fill", INK, f"caption:{title}", "-trim", "+repage", title_png)
    title_h = int(subprocess.run(["magick", "identify", "-format", "%h", title_png], check=True,
                                 capture_output=True, text=True).stdout)
    cmd += [title_png, "-geometry", f"+{x0}+{y + 12}", "-composite"]
    cmd += ["(", "-size", f"{text_w}x", "-background", "none", "-font", REG, "-pointsize", "42" if img else "48",
            "-fill", MUTED, "-interline-spacing", "8", f"caption:{caption or ' '}", ")",
            "-geometry", f"+{x0}+{y + 12 + title_h + 36}", "-composite"]
    if img:
        phone = f"{WORK}/p{i:02d}.png"
        # Rounded corners and a soft shadow around the screenshot.
        # A dark bezel with rounded corners; the screen radius (34) follows the bezel but stays small
        # enough that the status bar (clock, battery) is not clipped.
        run("magick", "-size", "464x996", "xc:none", "-fill", "#1F1F24",
            "-draw", "roundrectangle 0,0,463,995,52,52",
            # The screenshot is shrunk a little and padded with its own background colour, so the
            # status bar sits clear of the rounded screen corners.
            "(", f"{IMG}/{img}.png", "-resize", "95%", "-background", bg_of(f"{IMG}/{img}.png"),
            "-gravity", "center", "-extent", "432x964", "-gravity", "NorthWest",
            "-alpha", "set", "-type", "TrueColorAlpha",
            "(", "-size", "432x964", "xc:none", "-fill", "white", "-draw", "roundrectangle 0,0,431,963,34,34", ")",
            "-compose", "DstIn", "-composite", ")", "-compose", "over", "-geometry", "+16+16", "-composite",
            "(", "+clone", "-background", "#00000055", "-shadow", "60x18+0+12", ")", "+swap",
            "-background", "none", "-compose", "over", "-layers", "merge", "+repage",
            # Scale down so the phone keeps a clear margin from the top and bottom edges.
            "-resize", "x880", phone)
        # Centred vertically above the progress bar, with the same margin on the right.
        cmd += [phone, "-gravity", "East", "-geometry", "+170-5", "-composite", "-gravity", "NorthWest"]
    # Progress bar along the bottom.
    frac = i / (len(SLIDES) - 1)
    cmd += ["-fill", "#D5D9F5", "-draw", f"rectangle 0,{H-10} {W},{H}",
            "-fill", ACCENT, "-draw", f"rectangle 0,{H-10} {int(W*frac)},{H}"]
    if i == len(SLIDES) - 1:
        cmd += ["-font", REG, "-pointsize", "30", "-fill", MUTED, "-annotate", f"+{x0}+{H-80}",
                "Screens shown with sample data. Full guide: README, Walkthrough."]
    cmd.append(out)
    run(*cmd)
    return out


def main():
    os.makedirs(WORK, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    pngs = [slide_png(i, s) for i, s in enumerate(SLIDES)]
    durs = [s[4] for s in SLIDES]
    # MP4: each still becomes a clip, chained with crossfades.
    args = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    for p, d in zip(pngs, durs):
        args += ["-loop", "1", "-framerate", "30", "-t", str(d + FADE), "-i", p]
    chain, prev, offset = [], "[0:v]", 0.0
    for k in range(1, len(pngs)):
        offset += durs[k - 1]
        lab = f"[x{k}]"
        chain.append(f"{prev}[{k}:v]xfade=transition=fade:duration={FADE}:offset={offset - FADE * (k - 1):.3f}{lab}")
        prev = lab
    chain.append(f"{prev}format=yuv420p[v]")
    args += ["-filter_complex", ";".join(chain), "-map", "[v]", "-c:v", "libx264", "-preset", "slow",
             "-crf", "24", "-tune", "stillimage", "-movflags", "+faststart", f"{OUT}/quick-start.mp4"]
    run(*args)
    # GIF: hard cuts at half size, one shared palette.
    lst = f"{WORK}/gif.txt"
    with open(lst, "w") as f:
        for p, d in zip(pngs, durs):
            f.write(f"file '{p}'\nduration {d}\n")
        f.write(f"file '{pngs[-1]}'\n")
    run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
        "-vf", "fps=2,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=full[p];"
               "[b][p]paletteuse=dither=none", "-loop", "0", f"{OUT}/quick-start.gif")


if __name__ == "__main__":
    main()
