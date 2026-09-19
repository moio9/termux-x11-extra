#!/usr/bin/env python3
"""Temporary PulseAudio routing for the experimental Termux:X11 audio player."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys

SINK = 'termux_x11_audio'
PORT = 4714
STATE_DIR = Path(os.environ.get('XDG_STATE_HOME', str(Path.home() / '.local/state'))) / 'termux-x11-audio'
STATE = STATE_DIR / 'state.json'


def pactl(*args):
    result = subprocess.run(['pactl', *map(str, args)], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or 'pactl failed')
    return result.stdout.strip()


def listing(kind):
    # PulseAudio 17 omits module IDs from its JSON output.
    if kind == 'modules':
        rows = (line.split('\t') for line in pactl('list', 'short', 'modules').splitlines())
        return [{'index': int(row[0]), 'name': row[1], 'argument': row[2]} for row in rows]
    return json.loads(pactl('--format=json', 'list', kind))


def save(state):
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(state))
    temporary.chmod(0o600)
    temporary.replace(STATE)


def stop(state):
    sinks = {s['name']: s['index'] for s in listing('sinks')}
    current = sinks.get(SINK)
    previous = state['previous']
    fallback = previous if previous in sinks else next((s for s in sinks if s != SINK), None)
    if current is not None:
        if fallback is None:
            raise RuntimeError('No other audio output available; keeping the route for retry.')
        if pactl('get-default-sink') == SINK:
            pactl('set-default-sink', fallback)
        for stream in listing('sink-inputs'):
            if stream['sink'] == current:
                target = state.get('streams', {}).get(str(stream['index']), fallback)
                if target not in sinks:
                    target = fallback
                try:
                    pactl('move-sink-input', stream['index'], target)
                except RuntimeError:
                    # Streams may disappear while a game is shutting down.
                    if any(s['index'] == stream['index'] for s in listing('sink-inputs')):
                        raise
    # IDs can be reused after a PulseAudio restart; also check module identity.
    modules = {m['index']: m for m in listing('modules')}
    for module_id in reversed(state.get('modules', [])):
        module = modules.get(module_id)
        if module and module['name'] in ('module-null-sink', 'module-simple-protocol-tcp') and SINK in module['argument']:
            pactl('unload-module', module_id)
    if STATE.exists():
        STATE.unlink()


def start():
    if STATE.exists():
        print('Audio route already configured. Use stop before starting again.')
        return
    sinks = listing('sinks')
    if any(s['name'] == SINK for s in sinks):
        raise RuntimeError('An unmanaged termux_x11_audio sink already exists.')
    state = {'previous': pactl('get-default-sink'), 'modules': [], 'streams': {}}
    save(state)
    try:
        module = int(pactl('load-module', 'module-null-sink', 'sink_name=' + SINK,
                           'rate=48000', 'channels=2', 'format=s16le',
                           'sink_properties=device.description=TermuxX11_Audio'))
        state['modules'].append(module)
        save(state)
        module = int(pactl('load-module', 'module-simple-protocol-tcp',
                           'listen=127.0.0.1', 'port=' + str(PORT),
                           'source=' + SINK + '.monitor', 'record=true', 'playback=false',
                           'rate=48000', 'channels=2', 'format=s16le'))
        state['modules'].append(module)
        save(state)
        pactl('set-default-sink', SINK)
        names = {s['index']: s['name'] for s in sinks}
        for stream in listing('sink-inputs'):
            previous = names.get(stream['sink'])
            if previous:
                state['streams'][str(stream['index'])] = previous
                save(state)
                try:
                    pactl('move-sink-input', stream['index'], SINK)
                except RuntimeError:
                    if any(s['index'] == stream['index'] for s in listing('sink-inputs')):
                        raise
    except (Exception, KeyboardInterrupt):
        stop(state)
        raise
    print('Audio routed to Termux:X11. Enable Settings > Audio > Play audio through Termux:X11.')
    print('Restore the previous output with: python3 tools/termux-x11-audio.py stop')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'stop', 'status'))
    args = parser.parse_args()
    STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (STATE_DIR / 'lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.action == 'start':
            start()
        elif args.action == 'stop':
            if STATE.exists():
                stop(json.loads(STATE.read_text()))
            print('Previous audio output restored.')
        else:
            print('Configured' if STATE.exists() else 'Not configured')
            print('Default output:', pactl('get-default-sink'))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        sys.exit(str(error))
