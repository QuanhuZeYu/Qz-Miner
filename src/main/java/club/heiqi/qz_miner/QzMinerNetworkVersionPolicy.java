package club.heiqi.qz_miner;

import java.util.Map;

import cpw.mods.fml.relauncher.Side;

/** Qz-Miner 连接握手使用的严格 5.1 版本族策略。 */
final class QzMinerNetworkVersionPolicy {

    private static final int COMPATIBLE_MAJOR = 5;
    private static final int COMPATIBLE_MINOR = 1;

    private QzMinerNetworkVersionPolicy() {
    }

    /**
     * 判断 Forge 版本表是否可接受。
     *
     * <p>远端未声明本模组时只放行 mod-list 检查；一旦声明，双方版本都必须是完整、合法的 5.1
     * family。该放行不保证后续自定义网络通道可在无模组对端上安全运行。</p>
     *
     * @param localVersion 本地构建版本
     * @param remoteVersions Forge 提供的远端模组版本表
     * @param modId 本模组的精确 ID
     * @param side 发起检查的一侧
     * @return 是否通过版本检查
     */
    static boolean accepts(String localVersion, Map<String, String> remoteVersions, String modId, Side side) {
        if (remoteVersions == null || modId == null || side == null) {
            return false;
        }
        if (!remoteVersions.containsKey(modId)) {
            return true;
        }
        return isCompatibleFamily(localVersion) && isCompatibleFamily(remoteVersions.get(modId));
    }

    /**
     * 解析完整 ASCII SemVer 形态并判断 core 是否属于 5.1。
     *
     * @param version 待检查版本
     * @return 版本语法合法且 major/minor 为 5.1
     */
    static boolean isCompatibleFamily(String version) {
        if (version == null || version.length() == 0) {
            return false;
        }

        int[] number = new int[1];
        int cursor = parseCoreNumber(version, 0, number);
        if (cursor < 0 || number[0] != COMPATIBLE_MAJOR || !has(version, cursor, '.')) {
            return false;
        }
        cursor = parseCoreNumber(version, cursor + 1, number);
        if (cursor < 0 || number[0] != COMPATIBLE_MINOR || !has(version, cursor, '.')) {
            return false;
        }
        cursor = parseCoreNumber(version, cursor + 1, number);
        if (cursor < 0) {
            return false;
        }
        if (cursor == version.length()) {
            return true;
        }

        char qualifier = version.charAt(cursor);
        if (qualifier == '-') {
            int buildSeparator = parseIdentifiers(version, cursor + 1, true, true);
            if (buildSeparator < 0) {
                return false;
            }
            return buildSeparator == version.length()
                    || parseIdentifiers(version, buildSeparator + 1, false, false) == version.length();
        }
        return qualifier == '+'
                && parseIdentifiers(version, cursor + 1, false, false) == version.length();
    }

    private static int parseCoreNumber(String version, int start, int[] output) {
        int length = version.length();
        if (start >= length || !isAsciiDigit(version.charAt(start))) {
            return -1;
        }
        if (version.charAt(start) == '0' && start + 1 < length && isAsciiDigit(version.charAt(start + 1))) {
            return -1;
        }

        int value = 0;
        int cursor = start;
        while (cursor < length && isAsciiDigit(version.charAt(cursor))) {
            int digit = version.charAt(cursor) - '0';
            if (value > (Integer.MAX_VALUE - digit) / 10) {
                return -1;
            }
            value = value * 10 + digit;
            cursor++;
        }
        output[0] = value;
        return cursor;
    }

    private static int parseIdentifiers(
            String version, int start, boolean stopAtBuildSeparator, boolean rejectNumericLeadingZero) {
        int length = version.length();
        int identifierStart = start;
        boolean numeric = true;
        for (int cursor = start; cursor <= length; cursor++) {
            boolean atEnd = cursor == length;
            char current = atEnd ? '\0' : version.charAt(cursor);
            boolean atDot = !atEnd && current == '.';
            boolean atBuild = !atEnd && stopAtBuildSeparator && current == '+';
            if (atEnd || atDot || atBuild) {
                if (cursor == identifierStart
                        || (rejectNumericLeadingZero && numeric && cursor - identifierStart > 1
                                && version.charAt(identifierStart) == '0')) {
                    return -1;
                }
                if (atEnd || atBuild) {
                    return cursor;
                }
                identifierStart = cursor + 1;
                numeric = true;
                continue;
            }
            if (!isIdentifierCharacter(current)) {
                return -1;
            }
            if (!isAsciiDigit(current)) {
                numeric = false;
            }
        }
        return -1;
    }

    private static boolean has(String value, int index, char expected) {
        return index < value.length() && value.charAt(index) == expected;
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isIdentifierCharacter(char value) {
        return isAsciiDigit(value)
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value == '-';
    }
}
