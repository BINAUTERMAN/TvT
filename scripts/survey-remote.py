"""通过用户本机 SSH 密钥部署专用问卷服务器，不保存登录凭据。"""
import argparse
import sys
from pathlib import Path

import paramiko


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--script", type=Path)
    parser.add_argument("--upload", type=Path)
    parser.add_argument("--destination")
    args = parser.parse_args()
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(args.host, username="root", key_filename=str(args.key),
                       timeout=15, banner_timeout=15, auth_timeout=15,
                       allow_agent=False, look_for_keys=False)
        if args.upload:
            if not args.destination:
                parser.error("上传时需要 --destination")
            with client.open_sftp() as sftp:
                sftp.put(str(args.upload), args.destination)
            print("Uploaded", args.upload.name)
        if args.script:
            source = args.script.read_bytes()
            stdin, stdout, _ = client.exec_command("bash -se", timeout=600)
            stdout.channel.set_combine_stderr(True)
            stdin.write(source)
            stdin.channel.shutdown_write()
            for line in stdout:
                sys.stdout.write(line)
                sys.stdout.flush()
            raise SystemExit(stdout.channel.recv_exit_status())
    finally:
        client.close()


if __name__ == "__main__":
    main()
