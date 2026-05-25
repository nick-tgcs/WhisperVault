#!/usr/bin/env python3
"""
Injects a WAV file into the Android emulator's virtual microphone via gRPC.

Usage:
    python3 scripts/inject_audio.py <path/to/audio.wav>

The emulator must be running on localhost:8554 (default gRPC port for
emulator-5554). Requires the emulator to have been started WITHOUT -noaudio.

Protocol:
  emulator_controller.proto  →  EmulatorController.injectAudio(stream AudioPacket)
  AudioFormat: S16, Mono, 16000 Hz (Whisper's preferred sample rate)
  DeliveryMode: MODE_UNSPECIFIED (emulator pulls packets when ready)
"""

import sys
import os
import wave
import struct
import time
import grpc

# Add the proto_gen directory to the path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "proto_gen"))

import emulator_controller_pb2 as emu_pb2
import emulator_controller_pb2_grpc as emu_grpc

# Emulator gRPC endpoint (emulator-5554 uses port 8554)
EMULATOR_GRPC_ENDPOINT = "localhost:8554"


def get_grpc_token() -> str:
    """
    Reads the gRPC bearer token from the running emulator's ini file.
    The ini file is at /run/user/<uid>/avd/running/pid_<pid>.ini.
    """
    import glob, getpass, pwd
    uid = os.getuid()
    pattern = f"/run/user/{uid}/avd/running/pid_*.ini"
    for ini_path in glob.glob(pattern):
        with open(ini_path) as f:
            for line in f:
                if line.startswith("grpc.token="):
                    return line.split("=", 1)[1].strip()
    return ""

# Chunk size in samples — 20 ms at 16000 Hz = 320 samples = 640 bytes
CHUNK_SAMPLES = 320
TARGET_SAMPLE_RATE = 16000


def read_wav_as_s16_mono(path: str) -> tuple[bytes, int]:
    """
    Reads a WAV file and returns (raw_s16_mono_pcm_bytes, sample_rate).
    Converts stereo to mono by averaging channels.
    Resamples to TARGET_SAMPLE_RATE using ffmpeg if needed.
    """
    import subprocess, tempfile

    # Use ffmpeg to normalise to s16le mono at the target rate
    with tempfile.NamedTemporaryFile(suffix=".raw", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        subprocess.run(
            [
                "ffmpeg", "-y",
                "-i", path,
                "-ar", str(TARGET_SAMPLE_RATE),
                "-ac", "1",
                "-f", "s16le",
                tmp_path,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        with open(tmp_path, "rb") as f:
            return f.read(), TARGET_SAMPLE_RATE
    finally:
        os.unlink(tmp_path)


def audio_packet_generator(pcm_bytes: bytes, sample_rate: int):
    """Yields AudioPacket messages, 20 ms at a time."""
    chunk_bytes = CHUNK_SAMPLES * 2  # 2 bytes per S16 sample
    timestamp_us = int(time.time() * 1_000_000)
    us_per_chunk = int(CHUNK_SAMPLES / sample_rate * 1_000_000)

    fmt = emu_pb2.AudioFormat(
        samplingRate=sample_rate,
        channels=emu_pb2.AudioFormat.Mono,
        format=emu_pb2.AudioFormat.AUD_FMT_S16,
        mode=emu_pb2.AudioFormat.MODE_UNSPECIFIED,
    )

    for offset in range(0, len(pcm_bytes), chunk_bytes):
        chunk = pcm_bytes[offset : offset + chunk_bytes]
        # Pad last chunk if needed
        if len(chunk) < chunk_bytes:
            chunk = chunk + b"\x00" * (chunk_bytes - len(chunk))

        yield emu_pb2.AudioPacket(
            format=fmt,
            timestamp=timestamp_us,
            audio=chunk,
        )
        timestamp_us += us_per_chunk
        # Throttle to real-time to avoid flooding the queue
        time.sleep(us_per_chunk / 1_000_000)


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <path/to/audio.wav>")
        sys.exit(1)

    wav_path = sys.argv[1]
    if not os.path.exists(wav_path):
        print(f"Error: file not found: {wav_path}")
        sys.exit(1)

    print(f"Reading {wav_path} ...")
    pcm_bytes, sample_rate = read_wav_as_s16_mono(wav_path)
    duration_s = len(pcm_bytes) / (sample_rate * 2)
    print(f"  {duration_s:.2f}s of audio, {sample_rate} Hz, {len(pcm_bytes)} bytes")

    print(f"Connecting to emulator at {EMULATOR_GRPC_ENDPOINT} ...")
    token = get_grpc_token()
    # Emulator uses plain h2c (insecure) with Bearer token in per-call metadata.
    # grpc.local_channel_credentials() sends ALTS which the emulator rejects.
    channel = grpc.insecure_channel(EMULATOR_GRPC_ENDPOINT)
    stub = emu_grpc.EmulatorControllerStub(channel)
    metadata = [("authorization", f"Bearer {token}")] if token else []

    print("Injecting audio into virtual microphone ...")
    try:
        stub.injectAudio(audio_packet_generator(pcm_bytes, sample_rate), metadata=metadata)
        print("Done.")
    except grpc.RpcError as e:
        print(f"gRPC error: {e.code()} — {e.details()}")
        sys.exit(1)
    finally:
        channel.close()


if __name__ == "__main__":
    main()
