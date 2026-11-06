"""Run the installed debug instrumentation against host-local TCP/UDP echo servers.

Test: Native VPN enforcement and hostname attribution use host-local synthetic traffic.
Why: Java policy tests alone cannot prove native VPN sessions and IP caches discard stale allows.
Build/install the debug app and androidTest APK first. Requires an Android emulator.
"""
import argparse
from contextlib import ExitStack
import struct
import os
import re
from pathlib import Path
import socketserver
import subprocess
import threading
from release_runtime import instrumentation_passes


class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            try:
                data = self.request.recv(4096)
                if not data:
                    return
                self.request.sendall(data)
            except (ConnectionResetError, BrokenPipeError):
                return  # Blocking/expiry deliberately terminates a fixture connection.


class UdpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        data, connection = self.request
        connection.sendto(data, self.client_address)


class DnsReply(socketserver.BaseRequestHandler):
    """Answer only a synthetic TCP DNS query; never forward it elsewhere."""
    def handle(self):
        data = b''
        self.request.settimeout(5)
        while len(data) < 2 or len(data) < 2 + int.from_bytes(data[:2], 'big'):
            part = self.request.recv(512)
            if not part or len(data) + len(part) > 512:
                return
            data += part
        reply = dns_reply(data[2:])
        if reply:
            self.request.sendall(len(reply).to_bytes(2, 'big') + reply)


def dns_reply(data):
    for label, kind, answer in [(b'dns', 1, bytes([192, 0, 2, 92])),
                                (b'service', 65, b'\x00\x01\x00')]:
        question = bytes([len(label)]) + label + b'\x06native\x07invalid\0' + struct.pack('!HH', kind, 1)
        if len(data) == 12 + len(question) and data[12:] == question:
            reply = data[:2] + struct.pack('!HHHHH', 0x8180, 1, 1, 0, 0) + question
            return reply + b'\xc0\x0c' + struct.pack('!HHIH', kind, 1, 60, len(answer)) + answer
    return None  # This fixture never forwards arbitrary queries.


class UdpDnsReply(socketserver.BaseRequestHandler):
    def handle(self):
        data, connection = self.request
        reply = dns_reply(data)
        if reply:
            connection.sendto(reply, self.client_address)


class TcpServer(socketserver.ThreadingTCPServer):
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools' / 'adb.exe'))
    parser.add_argument('--serial', default='emulator-5590')
    parser.add_argument('--test-class', default='heartguard.heartguard.main.AppPauseNetworkDeviceTest')
    parser.add_argument('--dns', action='store_true', help='Also test the native DNS owner bridge using loopback TCP port 53.')
    args = parser.parse_args()
    if not re.fullmatch(r'heartguard\.heartguard\.main\.[A-Za-z]+DeviceTest(?:#[A-Za-z]+)?', args.test_class):
        parser.error('Select a DomainGuard device test class or method.')
    if not args.serial.startswith('emulator-'):
        parser.error('Use a disposable emulator.')
    with TcpServer(('127.0.0.1', 0), TcpEcho) as tcp:
        port = tcp.server_address[1]
        with socketserver.ThreadingUDPServer(('127.0.0.1', port), UdpEcho) as udp, ExitStack() as stack:
            if args.dns:
                dns = stack.enter_context(TcpServer(('127.0.0.1', 53), DnsReply))
                threading.Thread(target=dns.serve_forever, daemon=True).start()
                stack.callback(dns.shutdown)
                dns_udp = stack.enter_context(socketserver.ThreadingUDPServer(('127.0.0.1', 53), UdpDnsReply))
                threading.Thread(target=dns_udp.serve_forever, daemon=True).start()
                stack.callback(dns_udp.shutdown)
            for server in (tcp, udp):
                threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                result = subprocess.run([
                    args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'pauseNetworkPort', str(port), '-e', 'dnsFixture', str(args.dns).lower(), '-e', 'class',
                    args.test_class,
                    'heartguard.heartguard.main.mw.test/androidx.test.runner.AndroidJUnitRunner',
                ], capture_output=True, text=True, timeout=240)
                print(result.stdout)
                if result.returncode:
                    raise RuntimeError(result.stderr or 'Native VPN test failed.')
                instrumentation_passes(result.stdout)
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                if isinstance(error, subprocess.TimeoutExpired) and error.stdout:
                    output = error.stdout
                    print(output.decode('utf-8', errors='replace') if isinstance(output, bytes) else output)
                # Keep the first failure's service lifecycle evidence; never rerun to hide it.
                print('\nNative VPN failure diagnostics:', flush=True)
                try:
                    diagnostic = subprocess.run([
                        args.adb, '-s', args.serial, 'logcat', '-d', '-v', 'threadtime', '-t', '600',
                        'NetGuard.Service:I', 'NetGuard.RulesManager:W', 'NetGuard.JNI:W', 'TestRunner:I', '*:S',
                    ], capture_output=True, text=True, timeout=15)
                    print(diagnostic.stdout)
                    if diagnostic.returncode:
                        print('Diagnostics unavailable: ' + diagnostic.stderr)
                except subprocess.TimeoutExpired:
                    print('Diagnostics timed out.')
                raise
            finally:
                tcp.shutdown()
                udp.shutdown()


if __name__ == '__main__':
    main()
