#!/usr/bin/env python3
"""Drive the Pleos Virtual Console (emulator vehicle signals) for demos and manual tests.

Usage (run with: python3 -I tools/vc_scenario.py <command> ...):
  park                 gear P, speed 0, all doors closed
  drive [kmh]          gear D and the given speed (default 30)
  seat <-3..3>         driver seat climate level (above 0 heat, below 0 vent)
  defrost on|off       front windshield defroster
  cruise ready|off     driving-assist standby
  door <area> open|close   area: front_left, front_right, rear_left, rear_right
  signal left|right|off    turn signal lever
  steer <deg>          steering wheel angle while parked, as the app sees it (left is negative)
  odo <km>             odometer (whole km)
  temp <c>             driver set temperature, 16-30 in 0.5 steps
  light <spot> on|off  spot: driver, passenger, rear_left, cabin
  demo                 step through the 3-minute demo; press Enter before each vehicle change

Needs `adb forward tcp:8088 tcp:8088`. The console ignores input until a browser-session heartbeat makes it active,
so every command keeps a heartbeat running and waits for emulator_active and bridge_ready.
"""
import json
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
DOOR_FIELDS = {"front_left": "door_fl", "front_right": "door_fr", "rear_left": "door_rl", "rear_right": "door_rr"}


def request(method, path):
    req = urllib.request.Request(BASE + path, method=method, data=b"" if method in ("POST", "PUT") else None)
    with urllib.request.urlopen(req, timeout=5) as r:
        body = r.read().decode("utf-8", "replace")
    return body


class Heartbeat:
    def __init__(self):
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        while not self._stop.is_set():
            try:
                request("POST", "/browser/session?client=chageun-scenario")
            except OSError:
                pass
            self._stop.wait(1.0)

    def __enter__(self):
        self._thread.start()
        for _ in range(30):
            try:
                state = json.loads(request("GET", "/vehicle/driving-mode"))
                if state.get("emulator_active") and state.get("bridge_ready"):
                    return self
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise SystemExit("Virtual Console did not become active. Is the emulator running and port 8088 forwarded?")

    def __exit__(self, *exc):
        self._stop.set()


def put(path, label):
    request("PUT", path)
    print(f"  -> {label}")
    time.sleep(0.6)


def park():
    put("/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "speed 0")
    put("/vehicle/gear?gear=Park", "gear P")
    for area, field in DOOR_FIELDS.items():
        put(f"/vehicle/body?{field}=false", f"door {area} closed")


def drive(kmh=30):
    put("/vehicle/gear?gear=Drive", "gear D")
    put(f"/vehicle/controls?speed={kmh}&accelerator=20&brake=0&steering=0", f"speed {kmh} km/h")


def seat(level):
    put(f"/vehicle/hvac?driver_seat={int(level)}", f"driver seat level {level}")


def defrost(on):
    put(f"/vehicle/hvac?front_defroster={'true' if on else 'false'}", f"front defroster {'on' if on else 'off'}")


def cruise(ready):
    put(f"/vehicle/driving-mode?ad_mode_state={'ready' if ready else 'not_ready'}", f"driving assist {'standby' if ready else 'off'}")


def door(area, opened):
    put(f"/vehicle/body?{DOOR_FIELDS[area]}={'true' if opened else 'false'}", f"door {area} {'open' if opened else 'closed'}")


def signal(mode):
    value = {"left": "BlinkLeft", "right": "BlinkRight", "off": "Off"}[mode]
    put(f"/vehicle/turn-signal?mode={value}", f"turn signal {mode}")


def steer(deg):
    # Measured 2026-11-01: console steering=+30 reaches apps as -30 (left, AAOS PERF_STEERING_ANGLE convention),
    # so the console value is the opposite sign of what the app reads.
    put(f"/vehicle/controls?speed=0&accelerator=0&brake=0&steering={-int(deg)}", f"steering {int(deg)} deg (app view)")


def odo(km):
    put(f"/vehicle/odometer?traveled_distance_km={int(km)}", f"odometer {int(km)} km")


def temp(c):
    put(f"/vehicle/hvac?driver_temp={float(c)}", f"driver temperature {c}")


LIGHT_AREAS = {"driver": "front_left", "passenger": "front_right", "rear_left": "rear_left"}


def light(spot, on):
    value = "true" if on else "false"
    if spot == "cabin":
        put(f"/vehicle/body?cabin_light={value}", f"cabin light {'on' if on else 'off'}")
    else:
        put(f"/vehicle/reading-lights?area={LIGHT_AREAS[spot]}&value={value}", f"{spot} reading light {'on' if on else 'off'}")


def wait(msg):
    input(f"\n[{msg}] Enter를 누르면 다음 차량 신호를 보냅니다... ")


def demo():
    print("시연 준비: 정차 상태로 맞춥니다.")
    park(); seat(0); defrost(False); cruise(False); signal("off"); odo(12437); temp(23)
    for spot in ("driver", "passenger", "rear_left", "cabin"):
        light(spot, False)
    wait("0-2 추운 아침: 앱에서 '추운 아침'을 시작한 뒤")
    seat(2)
    wait("이유 퀴즈를 푼 뒤 '열선 끄기' 단계에서")
    seat(0)
    wait("0-4 김 서린 앞유리를 시작한 뒤")
    defrost(True)
    wait("0-7 주행 보조 버튼을 시작한 뒤")
    cruise(True)
    wait("퀴즈 2개를 푼 뒤 '대기 해제' 단계에서")
    cruise(False)
    wait("1화: 홈에서 '지하 4층 정전'을 시작한 뒤 (안내판: 오-왼-왼-오)")
    for mode in ("right", "off", "left", "off", "left", "off", "right"):
        signal(mode)
        time.sleep(0.8)
    wait("'방향지시등을 꺼 주세요'가 보이면")
    signal("off")
    wait("금고 번호 3 · 7을 넣은 뒤 핸들 금고에서")
    steer(-30)
    time.sleep(2.0)
    steer(70)
    time.sleep(2.0)
    wait("'핸들을 가운데로'가 보이면")
    steer(0)
    wait("보일러 기록계에서 (21.5도)")
    temp(21.5)
    wait("'온도 되돌리기'에서")
    temp(23)
    wait("거꾸로 켜는 불빛에서 (가족: 뒷좌석 왼쪽 → 운전석 → 조수석)")
    for spot in ("rear_left", "driver", "passenger"):
        light(spot, True)
        time.sleep(1.0)
    wait("탈출 성공 후 불 끄기에서")
    for spot in ("rear_left", "driver", "passenger"):
        light(spot, False)
    wait("안전 시연: 아무 퍼즐이나 시작한 뒤")
    drive(20)
    wait("멈춘 화면 확인 후, 다시 정차 상태로")
    park()
    defrost(False)
    print("\n시연 끝. '이어서 하기'는 정차 조건이 맞을 때만 눌립니다.")


def main(argv):
    if not argv:
        print(__doc__)
        return
    cmd, args = argv[0], argv[1:]
    with Heartbeat():
        if cmd == "park":
            park()
        elif cmd == "drive":
            drive(int(float(args[0])) if args else 30)  # the console rejects decimal speeds (HTTP 400)
        elif cmd == "seat":
            seat(args[0])
        elif cmd == "defrost":
            defrost(args[0] == "on")
        elif cmd == "cruise":
            cruise(args[0] == "ready")
        elif cmd == "door":
            door(args[0], args[1] == "open")
        elif cmd == "signal":
            signal(args[0])
        elif cmd == "steer":
            steer(args[0])
        elif cmd == "odo":
            odo(args[0])
        elif cmd == "temp":
            temp(args[0])
        elif cmd == "light":
            light(args[0], args[1] == "on")
        elif cmd == "demo":
            demo()
        else:
            print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
