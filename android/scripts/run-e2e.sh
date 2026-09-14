#!/bin/bash
# 清理测试残留进程并运行 verify-runtime e2e
# （bridge/工作台用宿主 node，引擎经 qemu；见 verify-runtime.py 头部说明）
cd /root/.codebuddy/artifact/mobilecode
for pat in "mock-openai.py --http 18080" "chat-bridge.js" "dist-cli/index.js --port 18933"; do
  for pid in $(ps -eo pid,args | grep -F "$pat" | grep -v grep | awk '{print $1}'); do
    kill "$pid" 2>/dev/null
  done
done
sleep 1
python3 android/scripts/verify-runtime.py e2e android/scripts/.stage/usr
