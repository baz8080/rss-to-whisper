"""Speaker turns in an audio file, printed as JSON [[start, end, speaker], ...]: sherpa-onnx's offline diarization."""
import argparse
import json
import shutil
import subprocess
import sys
from array import array

parser = argparse.ArgumentParser()
parser.add_argument("--segmentation", required=True)
parser.add_argument("--embedding", required=True)
parser.add_argument("--threshold", type=float, required=True)
parser.add_argument("--threads", type=int, required=True)
parser.add_argument("--check", action="store_true", help="check sherpa-onnx, ffmpeg and the models, and exit")
parser.add_argument("audio", nargs="?")
args = parser.parse_args()
if not args.check and not args.audio:
    parser.error("an audio file is needed unless --check is given")

try:
    import sherpa_onnx
except ImportError as e:
    sys.exit(f"sherpa-onnx is not installed for {sys.executable}: {e}")

if shutil.which("ffmpeg") is None:
    sys.exit("ffmpeg is not on PATH")

config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
    segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
        pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=args.segmentation), num_threads=args.threads),
    embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=args.embedding, num_threads=args.threads),
    clustering=sherpa_onnx.FastClusteringConfig(threshold=args.threshold))
if not config.validate():
    sys.exit("sherpa-onnx refused the models: check the segmentation and embedding paths")
diarizer = sherpa_onnx.OfflineSpeakerDiarization(config)
if args.check:
    sys.exit(0)

try:
    raw = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", args.audio, "-ar", "16000", "-ac", "1", "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
except subprocess.CalledProcessError as e:
    sys.exit(f"ffmpeg could not decode {args.audio}: {e.stderr.decode(errors='replace').strip()}")

# Freed before sherpa makes its own copy of the samples.
samples = array("f")
samples.frombytes(raw)
del raw
turns = diarizer.process(samples).sort_by_start_time()
print(json.dumps([[round(t.start, 2), round(t.end, 2), t.speaker] for t in turns]))
