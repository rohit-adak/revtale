# Cluster protocol notes

Observed by connecting to our own bikes. Facts marked **verified** were confirmed on a real bike.

## Transport
- BLE GATT service `5456534D-5647-5341-5342-454E544F5251`.
- Notifications from the bike: characteristic `0x5354`. Writes from the phone: `0x5352` (write with response).
- The OS bonds first (passkey shown on the cluster). There is no app-level login or server secret. **verified**

## Frames
- 20 bytes: `[0]` start (`5A` data/control, `5B` info/text), `[1]` data id, `[2..18]` payload, `[19]` `FF`.
- Bytes 2..18 are XOR-ed with one key byte. The key differs between bike families (`EB` on the RTR 160 4V
  **verified**). The app learns it from incoming frames, so it is never hard-coded.
- Bike → phone frames carry a checksum in byte 18: `255 − (sum of plain bytes 0..17 mod 256)`. **verified**

## Phone → bike (display/info only)
| Frame | Meaning |
|---|---|
| `5B 52 <text ≤17>` | Rider name. Sent 5× at 400 ms right after connecting. **verified** |
| `5B 4A …` | Phone status every 2 s: b2 = signal×16 + battery level (0–5), b3 overspeed km/h, b6–9 hh mm ss AM/PM (12 h), b12–14 dd mm yy. Drives the cluster clock. **verified** |
| `5B 4C <text ≤17>` | Custom line in the notification area. **verified** |
| `5B 43 <text>` | Caller name. **verified** |
| `5B 4A` b5 = 1 / 2 | Built-in "SMS SENT SUCCESS" / "SMS NOT SENT". **verified** |
| `5B 4A` b15 = n | Envelope icon with unread count `0n`; stays until cleared. **verified** |
| `5B 53/62/65/61/5C` | SMS text, music title/artist/album, location: no visible effect yet |

Text line font: draws `A-Z 0-9 ! " / : ; < = > ? @ [ \ ] ^ _` `` ` `` and space (lowercase is shown uppercase).
Draws nothing for `# $ % & ' ( ) * + , - . { | } ~`, control codes, or anything ≥ 0x80. There are no bitmaps or icons
in the text line. **verified**

## Navigation **verified** (arrow + distance + ETA shown)
1. Once: `5B 4C "Navigation"` then `5B 63 "Assist On"`, both sent **without XOR**, with the case kept.
2. About every 400 ms: `5A 4E` with the checksum in b18, then XOR-ed:
   `dist to turn (2, m) | minutes left (2) | distance left (3, m) | pictogram | 1 | status=1 | unit | alert | prefix | 0 0 0`.
   Pictogram 3 = right turn. The full pictogram table is still being mapped on a real cluster.
3. Alongside it: `5B 4F <maneuver text ≤16>` with the checksum, XOR-ed.
4. Road name `5A 49` (33 bytes, needs MTU ≥ 65): not confirmed yet.

## Bike → phone (1-based byte positions, plain)
- `5A10`: b3 speed, b4–6 odometer ÷10 km **verified**, b7 fuel bars, b17–18 RPM **verified**, b12 `0x80` = engine stopped.
- `5A10`: b10 top speed; b12 = 4 while the Info (i) switch is held **verified** (rider-confirmed, 11 Oct 2026 video);
  b12 = 3 while MODE is held (riding mode then changes) (filmed, medium confidence).
  While a call rings (5B43 shown), pressing i reads b12 = 2 for ~10 s instead of 4 **verified** (two calls, 11 Oct 2026).
- Nav distance to turn: the cluster draws 0–9 m and ≥ 100 m correctly, but 10–99 m only as `0` + the last digit
  (70 → "00m", 75 → "05m") under every prefix and unit value. **verified** (distance test video, 11 Oct 2026).
- `5A11`: b6 gear = low nibble (0 = N, 1–5; high nibble carries flags, seen 0x10 / 0x20), b18 riding mode = low nibble (1 = Urban, 2 = Sport, seen live on an RTR 160 4V; Rain not yet confirmed), b8 software version, b10 model. b11 bit `0x80` and b14 bit `0x20` toggle together and
  seem to follow cluster pop-ups / notification state. They are not a clean button signal.
- `5A18`: b12 battery voltage ×10 **verified** (12.5 V idle, ~11 V while cranking, 14.1–14.3 V charging), b9 engine
  temperature °C, b8 intake air °C (16-min city ride, 11 Oct 2026: engine 71 → 145, intake 33 → 57; intake ≈ outside air at
  a cold start, so both read straight °C without an offset).
- `5A19`: b7/b10 Trip A km, b14/b15 average speed.
- `5B42`: the bike's own Bluetooth address.
- Data arrives roughly once a second per id, so taps shorter than ~0.5 s can be missed.
