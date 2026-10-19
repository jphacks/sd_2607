"""Bounded UART capture/control without requesting reset; one COM owner only."""
import argparse
from pathlib import Path
import time
import re
import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('port')
    parser.add_argument('--command', choices=['status', 'download', 'audio on', 'audio off'])
    parser.add_argument('--audio-on', action='store_true', help='explicitly unmute before the requested command')
    parser.add_argument('--peers', nargs='*', help='explicit local isolation, empty list restores unrestricted peers')
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--require-ingress-speech', action='store_true', help='fail unless a newly persisted full event ID finishes with nonzero PCM')
    args = parser.parse_args()
    if not 1 <= args.seconds <= 300:
        parser.error('seconds must be 1..300')
    if args.peers is not None and (len(args.peers) > 8 or any(len(p) != 16 or any(c not in '0123456789abcdef' for c in p) for p in args.peers)):
        parser.error('peers must be at most eight lowercase 16-hex node IDs')
    args.log.parent.mkdir(parents=True, exist_ok=True)
    uart = serial.Serial(port=None, baudrate=115200, timeout=0.2, write_timeout=2)
    uart.dtr = False
    uart.rts = False
    uart.port = args.port
    uart.open()
    with uart, args.log.open('wb') as log:
        if args.peers is not None:
            uart.write(('peers ' + (' '.join(args.peers) if args.peers else '*') + '\n').encode('ascii'))
        if args.audio_on:
            uart.write(b'audio on\n')
        if args.command:
            uart.write((args.command + '\n').encode('ascii'))
        deadline = time.monotonic() + args.seconds
        total = 0
        while time.monotonic() < deadline and total < 8 * 1024 * 1024:
            data = uart.read(4096)
            total += len(data)
            log.write(data)
            if data:
                print(data.decode('utf-8', errors='replace'), end='', flush=True)
    if args.require_ingress_speech:
        text = args.log.read_text(encoding='utf-8', errors='replace')
        if any(marker in text for marker in ('abort() was called', 'Guru Meditation', 'Rebooting...')):
            raise SystemExit('FAIL: device crash/reboot during verification; inspect the retained UART log')
        saved = set(re.findall(r'ingress: id=([0-9a-f]{64}) persisted before advertise;', text))
        for event, samples, nonzero, chunks in re.findall(r'TTS done id=([0-9a-f]{64}) samples=(\d+) nonzero=(\d+) chunks=(\d+)', text):
            if event in saved and int(samples) > 0 and int(nonzero) > 0 and 'TTS start id=' + event in text:
                print(f'PROOF ingress_saved_tts id={event} samples={samples} nonzero={nonzero} chunks={chunks}')
                return
        raise SystemExit('FAIL: no matching persisted ingress -> TTS start -> nonzero PCM completion in bounded capture')


if __name__ == '__main__':
    main()
