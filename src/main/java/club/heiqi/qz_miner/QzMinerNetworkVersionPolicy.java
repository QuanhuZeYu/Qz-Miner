package club.heiqi.qz_miner;

import java.util.Map;

import cpw.mods.fml.relauncher.Side;

/**
 * Qz-Miner 连接握手使用的版本族策略。
 *
 * <p><b>族 = 本构建版本的 {@code major.minor}</b>，真源是制品版本 {@link Tags#VERSION}
 * （与 {@code @Mod.version}、{@code mcmod.info} 同源）。同族内忽略 patch、prerelease 与
 * build qualifier 互通；跨族 fail-closed。不兼容变更必须升新 minor——升 minor 即自动切族，
 * 无需（也不得）手改族常量。</p>
 *
 * <p><b>为什么族必须从制品版本推导</b>：族曾是写死的 {@code 5}/{@code 3} 常量，5.4.0 制品越出
 * 5.3 后双方在握手中互相拒绝（{@code Rejecting connection CLIENT: [FMLMod:qz_miner{5.4.0}]}），
 * 每次 minor 升级都会复发。注意 FML 的「The mod ... accepts its own version」自检读的是
 * {@code @Mod.acceptableRemoteVersions} 区间（本模组留空，退化为字符串精确相等），
 * <b>不调用</b>本策略，因此那条日志不能作为策略放行的证据。</p>
 */
final class QzMinerNetworkVersionPolicy {

    /** 本构建版本所属的兼容族（{@code major.minor}）。 */
    private static final int FAMILY_MAJOR;
    private static final int FAMILY_MINOR;

    static {
        int[] family = parseFamily(Tags.VERSION);
        if (family == null) {
            // 制品版本由构建注入，合法 SemVer 是它的前置条件；不合法就在这里显式失败，
            // 不放行一个「族未知」的握手策略。
            throw new IllegalStateException("制品版本不是合法 SemVer，无法确定联机兼容族：" + Tags.VERSION);
        }
        FAMILY_MAJOR = family[0];
        FAMILY_MINOR = family[1];
    }

    private QzMinerNetworkVersionPolicy() {
    }

    /**
     * 判断 Forge 版本表是否可接受。
     *
     * <p>远端未声明本模组时只放行 mod-list 检查；一旦声明，双方版本都必须属于本构建版本的
     * {@code major.minor} 族。该放行不保证后续自定义网络通道可在无模组对端上安全运行。</p>
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
     * 解析完整 ASCII SemVer 形态并判断其 {@code major.minor} 是否等于本构建族。
     *
     * @param version 待检查版本
     * @return 版本语法合法且与本构建版本同族
     */
    static boolean isCompatibleFamily(String version) {
        int[] family = parseFamily(version);
        return family != null && family[0] == FAMILY_MAJOR && family[1] == FAMILY_MINOR;
    }

    /**
     * 取版本的族（{@code major.minor}），同时完成全部语法校验。
     *
     * <p>语法口径与历史实现逐条一致：core 三段必须完整、无前导零、不溢出；qualifier 只接受
     * SemVer 的 {@code -prerelease} 与 {@code +build} 形态。任一不合法返回 {@code null}——
     * 语法不合法与跨族是两类拒绝，但对握手都是 fail-closed。</p>
     *
     * @param version 待解析版本
     * @return 长度 2 的数组 {@code {major, minor}}；语法不合法时为 {@code null}
     */
    private static int[] parseFamily(String version) {
        if (version == null || version.length() == 0) {
            return null;
        }

        int[] number = new int[1];
        int cursor = parseCoreNumber(version, 0, number);
        if (cursor < 0 || !has(version, cursor, '.')) {
            return null;
        }
        int major = number[0];
        cursor = parseCoreNumber(version, cursor + 1, number);
        if (cursor < 0 || !has(version, cursor, '.')) {
            return null;
        }
        int minor = number[0];
        cursor = parseCoreNumber(version, cursor + 1, number);
        if (cursor < 0) {
            return null;
        }
        if (!hasValidQualifier(version, cursor)) {
            return null;
        }
        return new int[] {major, minor};
    }

    private static boolean hasValidQualifier(String version, int cursor) {
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
