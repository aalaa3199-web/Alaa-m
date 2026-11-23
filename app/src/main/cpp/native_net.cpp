#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <android/log.h>

#define LOG_TAG "AlaaNativeNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string trim(const std::string &s) {
    size_t start = 0;
    while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) {
        ++start;
    }
    size_t end = s.size();
    while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) {
        --end;
    }
    return s.substr(start, end - start);
}

std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return s;
}

std::string escapeJson(const std::string &input) {
    std::ostringstream out;
    for (unsigned char c : input) {
        switch (c) {
            case '\"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    out << "\\u00";
                    const char *hex = "0123456789abcdef";
                    out << hex[(c >> 4) & 0xF] << hex[c & 0xF];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

bool isValidIpv4(const std::string &ip) {
    struct in_addr addr {};
    return inet_pton(AF_INET, ip.c_str(), &addr) == 1;
}

bool isValidMac(const std::string &mac) {
    if (mac.size() != 17) return false;
    for (int i = 0; i < 17; ++i) {
        if (i % 3 == 2) {
            if (mac[i] != ':') return false;
        } else {
            if (!std::isxdigit(static_cast<unsigned char>(mac[i]))) return false;
        }
    }
    return true;
}

bool isSafeDomain(const std::string &domain) {
    if (domain.empty() || domain.size() > 253) return false;
    for (unsigned char c : domain) {
        if (!(std::isalnum(c) || c == '.' || c == '-')) {
            return false;
        }
    }
    return true;
}

std::string resolveServiceName(int port) {
    struct servent *srv = getservbyport(htons(static_cast<uint16_t>(port)), "tcp");
    if (srv != nullptr && srv->s_name != nullptr && srv->s_name[0] != '\0') {
        return std::string(srv->s_name);
    }
    switch (port) {
        case 21: return "ftp";
        case 22: return "ssh";
        case 23: return "telnet";
        case 53: return "domain";
        case 80: return "http";
        case 110: return "pop3";
        case 135: return "msrpc";
        case 139: return "netbios-ssn";
        case 143: return "imap";
        case 443: return "https";
        case 445: return "microsoft-ds";
        case 554: return "rtsp";
        case 1900: return "upnp";
        case 5353: return "mdns";
        case 62078: return "iphone-sync";
        case 8080: return "http-proxy";
        case 8443: return "https-alt";
        default: return "tcp-" + std::to_string(port);
    }
}

bool isTcpPortOpen(const std::string &ip, int port, int timeoutMs = 120) {
    if (!isValidIpv4(ip) || port <= 0 || port > 65535) return false;

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    }

    struct sockaddr_in addr {};
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) <= 0) {
        close(sock);
        return false;
    }

    int res = connect(sock, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr));
    bool open = false;

    if (res == 0) {
        open = true;
    } else if (errno == EINPROGRESS) {
        fd_set wfds;
        FD_ZERO(&wfds);
        FD_SET(sock, &wfds);

        struct timeval tv {};
        tv.tv_sec = timeoutMs / 1000;
        tv.tv_usec = (timeoutMs % 1000) * 1000;

        int sel = select(sock + 1, nullptr, &wfds, nullptr, &tv);
        if (sel > 0 && FD_ISSET(sock, &wfds)) {
            int soError = 0;
            socklen_t len = sizeof(soError);
            if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &soError, &len) == 0 && soError == 0) {
                open = true;
            }
        }
    }

    close(sock);
    return open;
}

std::string guessOsFromOpenPorts(const std::vector<int> &openPorts, const std::string &hostname) {
    std::string h = toLower(hostname);
    bool has445 = false;
    bool has135 = false;
    bool has62078 = false;
    bool has554 = false;
    bool has8080 = false;

    for (int p : openPorts) {
        if (p == 445) has445 = true;
        if (p == 135) has135 = true;
        if (p == 62078) has62078 = true;
        if (p == 554) has554 = true;
        if (p == 8080) has8080 = true;
    }

    if (h.find("iphone") != std::string::npos || h.find("ipad") != std::string::npos || has62078) {
        return "iOS / iPadOS";
    }
    if (h.find("android") != std::string::npos || h.find("xiaomi") != std::string::npos ||
        h.find("redmi") != std::string::npos || h.find("poco") != std::string::npos) {
        return "Android / MIUI";
    }
    if (h.find("windows") != std::string::npos || h.find("desktop-") != std::string::npos ||
        h.find("win-") != std::string::npos || (has445 && has135)) {
        return "Windows";
    }
    if (h.find("macbook") != std::string::npos || h.find("imac") != std::string::npos ||
        h.find("macos") != std::string::npos) {
        return "macOS";
    }
    if (h.find("tv") != std::string::npos || h.find("cam") != std::string::npos || has554) {
        return "Smart TV / IoT";
    }
    if (has445) {
        return "Windows / Samba";
    }
    if (has8080) {
        return "Embedded / Router / IoT";
    }
    return "Linux / Embedded";
}

std::string reverseDnsLookup(const std::string &ip) {
    struct sockaddr_in sa {};
    std::memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    if (inet_pton(AF_INET, ip.c_str(), &sa.sin_addr) != 1) {
        return "Unknown-Device";
    }

    char host[NI_MAXHOST];
    std::memset(host, 0, sizeof(host));
    int status = getnameinfo(
        reinterpret_cast<struct sockaddr *>(&sa),
        sizeof(sa),
        host,
        sizeof(host),
        nullptr,
        0,
        NI_NAMEREQD
    );
    if (status == 0 && host[0] != '\0') {
        return std::string(host);
    }
    return "Device-" + ip;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_alaa_m_RootNetEngine_nativeScanDevicePortsJson(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jIp,
        jstring jHostname) {

    const char *cIp = env->GetStringUTFChars(jIp, nullptr);
    const char *cHost = env->GetStringUTFChars(jHostname, nullptr);

    std::string ip = cIp ? trim(cIp) : "";
    std::string host = cHost ? trim(cHost) : "";

    if (cIp) env->ReleaseStringUTFChars(jIp, cIp);
    if (cHost) env->ReleaseStringUTFChars(jHostname, cHost);

    if (!isValidIpv4(ip)) {
        return env->NewStringUTF("{\"openCount\":0,\"os\":\"Unknown\",\"ports\":[]}");
    }

    if (host.empty() || host.rfind("Device-", 0) == 0) {
        std::string resolved = reverseDnsLookup(ip);
        if (!resolved.empty()) host = resolved;
    }

    static const int commonPorts[] = {
        21, 22, 23, 53, 80, 110, 135, 139, 143, 443,
        445, 554, 1900, 5353, 8080, 8443, 62078
    };

    std::vector<int> openPorts;
    for (int port : commonPorts) {
        if (isTcpPortOpen(ip, port, 110)) {
            openPorts.push_back(port);
        }
    }

    std::string osName = guessOsFromOpenPorts(openPorts, host);

    std::ostringstream json;
    json << "{";
    json << "\"openCount\":" << openPorts.size() << ",";
    json << "\"os\":\"" << escapeJson(osName) << "\",";
    json << "\"hostname\":\"" << escapeJson(host) << "\",";
    json << "\"ports\":[";
    for (size_t i = 0; i < openPorts.size(); ++i) {
        if (i > 0) json << ",";
        int p = openPorts[i];
        std::string svc = resolveServiceName(p);
        json << "{\"port\":" << p << ",\"service\":\"" << escapeJson(svc) << "\"}";
    }
    json << "]}";

    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_alaa_m_RootNetEngine_nativeResolveHostname(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jIp) {
    const char *cIp = env->GetStringUTFChars(jIp, nullptr);
    std::string ip = cIp ? trim(cIp) : "";
    if (cIp) env->ReleaseStringUTFChars(jIp, cIp);

    if (!isValidIpv4(ip)) {
        return env->NewStringUTF("Unknown-Device");
    }
    std::string name = reverseDnsLookup(ip);
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_alaa_m_RootNetEngine_nativeBuildIptablesCommand(
        JNIEnv *env,
        jobject /* thiz */,
        jstring jAction,
        jstring jTarget,
        jstring jExtra) {

    const char *cAction = env->GetStringUTFChars(jAction, nullptr);
    const char *cTarget = env->GetStringUTFChars(jTarget, nullptr);
    const char *cExtra = env->GetStringUTFChars(jExtra, nullptr);

    std::string action = cAction ? trim(cAction) : "";
    std::string target = cTarget ? trim(cTarget) : "";
    std::string extra = cExtra ? trim(cExtra) : "";

    if (cAction) env->ReleaseStringUTFChars(jAction, cAction);
    if (cTarget) env->ReleaseStringUTFChars(jTarget, cTarget);
    if (cExtra) env->ReleaseStringUTFChars(jExtra, cExtra);

    std::ostringstream cmd;

    if (action == "BLOCK_DEVICE_IP") {
        if (!isValidIpv4(target)) return env->NewStringUTF("");
        cmd << "iptables -C FORWARD -s " << target << " -j DROP 2>/dev/null || iptables -I FORWARD 1 -s " << target << " -j DROP; "
            << "iptables -C FORWARD -d " << target << " -j DROP 2>/dev/null || iptables -I FORWARD 1 -d " << target << " -j DROP; "
            << "iptables -C INPUT -s " << target << " -j DROP 2>/dev/null || iptables -I INPUT 1 -s " << target << " -j DROP";
    } else if (action == "UNBLOCK_DEVICE_IP") {
        if (!isValidIpv4(target)) return env->NewStringUTF("");
        cmd << "iptables -D FORWARD -s " << target << " -j DROP 2>/dev/null || true; "
            << "iptables -D FORWARD -d " << target << " -j DROP 2>/dev/null || true; "
            << "iptables -D INPUT -s " << target << " -j DROP 2>/dev/null || true";
    } else if (action == "BLOCK_DEVICE_MAC") {
        if (!isValidMac(target)) return env->NewStringUTF("");
        cmd << "iptables -C FORWARD -m mac --mac-source " << target << " -j DROP 2>/dev/null || iptables -I FORWARD 1 -m mac --mac-source " << target << " -j DROP; "
            << "iptables -C INPUT -m mac --mac-source " << target << " -j DROP 2>/dev/null || iptables -I INPUT 1 -m mac --mac-source " << target << " -j DROP";
    } else if (action == "UNBLOCK_DEVICE_MAC") {
        if (!isValidMac(target)) return env->NewStringUTF("");
        cmd << "iptables -D FORWARD -m mac --mac-source " << target << " -j DROP 2>/dev/null || true; "
            << "iptables -D INPUT -m mac --mac-source " << target << " -j DROP 2>/dev/null || true";
    } else if (action == "BLOCK_ALL_NETWORK") {
        cmd << "iptables -N ALAA_LOCKDOWN 2>/dev/null || true; "
            << "iptables -F ALAA_LOCKDOWN 2>/dev/null || true; "
            << "iptables -A ALAA_LOCKDOWN -j DROP; "
            << "iptables -C FORWARD -j ALAA_LOCKDOWN 2>/dev/null || iptables -I FORWARD 1 -j ALAA_LOCKDOWN; "
            << "iptables -C OUTPUT -p tcp --dport 80 -j DROP 2>/dev/null || iptables -I OUTPUT 1 -p tcp --dport 80 -j DROP; "
            << "iptables -C OUTPUT -p tcp --dport 443 -j DROP 2>/dev/null || iptables -I OUTPUT 1 -p tcp --dport 443 -j DROP";
    } else if (action == "UNBLOCK_ALL_NETWORK") {
        cmd << "iptables -D FORWARD -j ALAA_LOCKDOWN 2>/dev/null || true; "
            << "iptables -F ALAA_LOCKDOWN 2>/dev/null || true; "
            << "iptables -X ALAA_LOCKDOWN 2>/dev/null || true; "
            << "iptables -D OUTPUT -p tcp --dport 80 -j DROP 2>/dev/null || true; "
            << "iptables -D OUTPUT -p tcp --dport 443 -j DROP 2>/dev/null || true";
    } else if (action == "BLOCK_SITE_FOR_IP") {
        if (!isValidIpv4(target) || !isSafeDomain(extra)) return env->NewStringUTF("");
        cmd << "for ip in $(getent ahostsv4 " << extra << " 2>/dev/null | awk '{print $1}' | sort -u); do "
            << "  iptables -C FORWARD -s " << target << " -d \"$ip\" -j DROP 2>/dev/null || iptables -I FORWARD 1 -s " << target << " -d \"$ip\" -j DROP; "
            << "  iptables -C OUTPUT -d \"$ip\" -j DROP 2>/dev/null || iptables -I OUTPUT 1 -d \"$ip\" -j DROP; "
            << "done";
    } else if (action == "ALLOW_SITE_FOR_IP") {
        if (!isValidIpv4(target) || !isSafeDomain(extra)) return env->NewStringUTF("");
        cmd << "for ip in $(getent ahostsv4 " << extra << " 2>/dev/null | awk '{print $1}' | sort -u); do "
            << "  iptables -D FORWARD -s " << target << " -d \"$ip\" -j DROP 2>/dev/null || true; "
            << "  iptables -D OUTPUT -d \"$ip\" -j DROP 2>/dev/null || true; "
            << "done";
    }

    LOGI("Built root command for action=%s", action.c_str());
    return env->NewStringUTF(cmd.str().c_str());
}

}
