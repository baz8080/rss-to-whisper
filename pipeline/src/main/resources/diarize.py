"""Speaker turns in an audio file and a voice print per speaker, printed as JSON {"turns": [[start, end, speaker], ...],
"voices": {speaker: {"seconds": s, "print": [...]}}}: sherpa-onnx's offline diarization, then its speaker embedding."""
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
parser.add_argument("--turns", help="a JSON file of turns to print voices for, instead of diarizing")
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
# Stored turns need only the embedding model; the check loads both.
diarizer = sherpa_onnx.OfflineSpeakerDiarization(config) if args.check or not args.turns else None
extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config.embedding)
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
if args.turns:
    with open(args.turns) as f:
        turns = [(float(s), float(e), int(v)) for s, e, v in json.load(f)]
else:
    turns = [(round(t.start, 2), round(t.end, 2), t.speaker) for t in diarizer.process(samples).sort_by_start_time()]

RATE, MIN_PIECE, PIECE = 16000, 1.0, 10.0


def embed(start, end):
    stream = extractor.create_stream()
    stream.accept_waveform(RATE, samples[int(start * RATE):int(end * RATE)])
    stream.input_finished()
    e = extractor.compute(stream)
    norm = sum(x * x for x in e) ** 0.5
    return [x / norm for x in e] if norm > 0 else None


# A speaker's print: the duration-weighted mean of its pieces' unit embeddings, made unit length.
# Turns are cut into 10 s pieces, since the embedding model refuses inputs past about two minutes.
sums, seconds = {}, {}
for start, end, speaker in turns:
    if end - start < MIN_PIECE:
        continue
    p = start
    while p < end:
        q = min(p + PIECE, end)
        if q - p >= MIN_PIECE:
            e = embed(p, q)
            if e is None:
                p = q
                continue
            acc = sums.setdefault(speaker, [0.0] * len(e))
            for i, x in enumerate(e):
                acc[i] += x * (q - p)
        p = q
    if speaker in sums:
        seconds[speaker] = seconds.get(speaker, 0.0) + end - start
voices = {}
for speaker, acc in sums.items():
    norm = sum(x * x for x in acc) ** 0.5
    voices[str(speaker)] = {"seconds": round(seconds[speaker], 1), "print": [round(x / norm, 5) for x in acc]}
print(json.dumps({"turns": [list(t) for t in turns], "voices": voices}))
