"""Run the installed debug instrumentation against host-local TCP/UDP echo servers.

Test: Existing and new connections are blocked again when an app pause expires.
Why: Java policy tests alone cannot prove native VPN sessions discard stale allows.
Build/install the debug app and androidTest APK first. Requires an Android emulator.
"""
import argparse
import os
from pathlib import Path
import socketserver
import subprocess
import threading


class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(4096)
            if not data:
                return
            self.request.sendall(data)


class UdpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        data, connection = self.request
        connection.sendto(data, self.client_address)


class TcpServer(socketserver.ThreadingTCPServer):
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools' / 'adb.exe'))
    parser.add_argument('--serial', default='emulator-5590')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Use a disposable emulator.')
    with TcpServer(('127.0.0.1', 0), TcpEcho) as tcp:
        port = tcp.server_address[1]
        with socketserver.ThreadingUDPServer(('127.0.0.1', port), UdpEcho) as udp:
            for server in (tcp, udp):
                threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                result = subprocess.run([
                    args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'pauseNetworkPort', str(port), '-e', 'class',
                    'heartguard.heartguard.main.AppPauseNetworkDeviceTest',
                    'heartguard.heartguard.main.mw.test/androidx.test.runner.AndroidJUnitRunner',
                ], capture_output=True, text=True, timeout=120)
                print(result.stdout)
                if result.returncode or 'OK (1 test)' not in result.stdout:
                    raise SystemExit(result.stderr or 'Native pause test failed.')
            finally:
                tcp.shutdown()
                udp.shutdown()


if __name__ == '__main__':
    main()
