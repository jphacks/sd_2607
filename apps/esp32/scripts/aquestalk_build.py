"""Link the locally acquired evaluation SDK; never download or vendor it."""
from pathlib import Path

Import("env")

sdk = Path(env.subst("$PROJECT_DIR")) / "private/aquestalk"
required = [sdk / "src/aquestalk.h", sdk / "src/esp32/libaquestalk_s.a",
            sdk / "generated/LocalAquesTalkConfig.h"]
if not all(path.is_file() for path in required):
    raise RuntimeError("Local AquesTalk SDK missing. Run: uv run python setup_tts.py <SDK ZIP>")
env.Append(CPPPATH=[str(sdk / "src"), str(sdk / "generated")],
           LIBPATH=[str(sdk / "src/esp32")], LIBS=["aquestalk_s"])
