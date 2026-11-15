#!/usr/bin/env bash
# UNTESTED ON HARDWARE — not part of the demo build
#
# Turns a Raspberry Pi (Raspberry Pi OS Bookworm, built-in Wi-Fi) into a WPA2 access point with captive DNS that is
# also a full OfflineMesh host (desktop node + guest web client on :8090, port 80 redirected to it).
#
# On your computer:  ./gradlew :desktop:installDist      (makes desktop/build/install/desktop)
# Copy that folder to the Pi, then on the Pi:  sudo ./setup.sh /path/to/desktop
# Env overrides: IFACE=wlan0 AP_IP=10.42.0.1 SSID=OfflineMesh COUNTRY=US CHANNEL=6
set -euo pipefail

DIST=${1:?usage: sudo ./setup.sh <desktop installDist folder, the one with bin/desktop>}
IFACE=${IFACE:-wlan0}
AP_IP=${AP_IP:-10.42.0.1}
SSID=${SSID:-OfflineMesh}
COUNTRY=${COUNTRY:-US}
CHANNEL=${CHANNEL:-6}
NET=${AP_IP%.*}

[ "$(id -u)" = 0 ] || { echo "run as root: sudo $0 $*" >&2; exit 1; }
[ -x "$DIST/bin/desktop" ] || { echo "$DIST/bin/desktop not found (run ./gradlew :desktop:installDist)" >&2; exit 1; }

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y hostapd dnsmasq iptables openjdk-17-jre-headless python3

# Node install, its own user, data in /var/lib/offlinemesh (identity key lives there: back it up, keep it 0700).
id offlinemesh >/dev/null 2>&1 || useradd --system --home /var/lib/offlinemesh --shell /usr/sbin/nologin offlinemesh
rm -rf /opt/offlinemesh && cp -r "$DIST" /opt/offlinemesh

# Wi-Fi passphrase: generated once, reused on re-runs. token_urlsafe(18) gives 24 chars (144 bits).
mkdir -p /etc/offlinemesh && chmod 700 /etc/offlinemesh
PASSFILE=/etc/offlinemesh/wifi-pass
[ -s "$PASSFILE" ] || (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(18))' > "$PASSFILE")
PASS=$(cat "$PASSFILE")
[ "${#PASS}" -ge 16 ] || { echo "passphrase too short" >&2; exit 1; }

# Keep NetworkManager off the AP interface (Bookworm uses NM).
if [ -d /etc/NetworkManager/conf.d ]; then
  printf '[keyfile]\nunmanaged-devices=interface-name:%s\n' "$IFACE" > /etc/NetworkManager/conf.d/offlinemesh.conf
  systemctl reload NetworkManager || true
fi

# ap_isolate: stations cannot reach each other, only the Pi. With a shared passphrase any guest could otherwise
# ARP-spoof another guest and rewrite the plain-HTTP JavaScript on its way.
umask 077
cat > /etc/hostapd/hostapd.conf <<EOF
interface=$IFACE
driver=nl80211
ssid=$SSID
country_code=$COUNTRY
hw_mode=g
channel=$CHANNEL
wmm_enabled=1
ap_isolate=1
auth_algs=1
wpa=2
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
wpa_passphrase=$PASS
EOF
umask 022

# Captive DNS: every name answers with the Pi. No upstream (offline by design).
cat > /etc/dnsmasq.d/offlinemesh.conf <<EOF
interface=$IFACE
except-interface=lo
bind-dynamic
no-resolv
dhcp-range=$NET.10,$NET.250,255.255.255.0,12h
dhcp-option=option:router,$AP_IP
dhcp-option=option:dns-server,$AP_IP
address=/#/$AP_IP
EOF

# Interface address and the port 80 -> 8090 redirect, applied at every boot before hostapd/dnsmasq.
cat > /etc/systemd/system/offlinemesh-net.service <<EOF
[Unit]
Description=OfflineMesh AP address and port 80 redirect
Before=hostapd.service dnsmasq.service offlinemesh.service
After=network-pre.target
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/sbin/rfkill unblock wlan
ExecStart=/usr/sbin/ip link set $IFACE up
ExecStart=/usr/sbin/ip addr replace $AP_IP/24 dev $IFACE
ExecStart=/bin/sh -c 'iptables -t nat -C PREROUTING -i $IFACE -p tcp --dport 80 -j REDIRECT --to-ports 8090 2>/dev/null || iptables -t nat -A PREROUTING -i $IFACE -p tcp --dport 80 -j REDIRECT --to-ports 8090'
[Install]
WantedBy=multi-user.target
EOF

# The mesh host. Local UI stays on 127.0.0.1:8081 (reach it with ssh -L 8081:localhost:8081). Guests use :8090 (or :80).
cat > /etc/systemd/system/offlinemesh.service <<EOF
[Unit]
Description=OfflineMesh desktop node (mesh host + guest web client on :8090)
Wants=offlinemesh-net.service
After=offlinemesh-net.service
[Service]
User=offlinemesh
StateDirectory=offlinemesh
StateDirectoryMode=0700
ExecStart=/opt/offlinemesh/bin/desktop --data /var/lib/offlinemesh --ui-port 8081 --listen 9001 --guest-port 8090
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl unmask hostapd
systemctl enable offlinemesh-net hostapd dnsmasq offlinemesh
systemctl restart offlinemesh-net hostapd dnsmasq offlinemesh

echo
echo "Wi-Fi:  $SSID"
echo "Pass:   $PASS   (also in $PASSFILE, root only)"
echo "Guests: http://$AP_IP/  (the guest PIN is in: journalctl -u offlinemesh | grep pin)"
echo "Wi-Fi QR text: WIFI:T:WPA;S:$SSID;P:$PASS;;"
