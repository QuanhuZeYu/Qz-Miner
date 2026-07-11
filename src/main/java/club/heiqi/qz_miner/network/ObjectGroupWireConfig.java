package club.heiqi.qz_miner.network;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import io.netty.buffer.ByteBuf;

/**
 * 对象组 C2S 的有界原始 wire 值；不在 Netty 线程解析注册表或发布玩家状态。
 */
public final class ObjectGroupWireConfig {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    public static final int MAX_GROUPS = ObjectGroupRuleSet.MAX_GROUPS;
    public static final int MAX_MEMBERS = ObjectGroup.MAX_MEMBERS;
    public static final int MAX_TOTAL_MEMBERS = ObjectGroupRuleSet.MAX_TOTAL_MEMBERS;
    public static final int MAX_STRING_BYTES = 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int protocolVersion;
    private final long revision;
    private final List<RawGroup> groups;
    private final boolean valid;

    private ObjectGroupWireConfig(int protocolVersion, long revision, List<RawGroup> groups, boolean valid) {
        this.protocolVersion = protocolVersion;
        this.revision = revision;
        this.groups = Collections.unmodifiableList(new ArrayList<RawGroup>(groups));
        this.valid = valid;
    }

    /** 从客户端已验证不可变规则集创建 wire 值。 */
    public static ObjectGroupWireConfig fromRuleSet(long revision, ObjectGroupRuleSet rules) {
        if (revision < 0L || rules == null) {
            throw new IllegalArgumentException("revision/rules must be valid");
        }
        List<RawGroup> groups = new ArrayList<RawGroup>();
        for (ObjectGroup group : rules.groups()) {
            List<String> members = new ArrayList<String>();
            for (ObjectGroupSelector selector : group.members()) {
                members.add(selector.canonical());
            }
            groups.add(new RawGroup(group.id(), members));
        }
        ObjectGroupWireConfig config = new ObjectGroupWireConfig(PROTOCOL_VERSION, revision, groups, true);
        if (config.encodedSize() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("object group payload exceeds 32KiB");
        }
        return config;
    }

    /** 从 ByteBuf 捕获有界原始值；失败时返回 invalid，调用方必须整包拒绝。 */
    public static ObjectGroupWireConfig read(ByteBuf buf) {
        if (buf == null || buf.readableBytes() > MAX_PAYLOAD_BYTES) {
            return invalid();
        }
        try {
            int version = buf.readInt();
            long revision = buf.readLong();
            int groupCount = buf.readInt();
            if (revision < 0L || groupCount < 0 || groupCount > MAX_GROUPS) {
                return invalid();
            }
            List<RawGroup> groups = new ArrayList<RawGroup>(groupCount);
            int totalMembers = 0;
            for (int i = 0; i < groupCount; i++) {
                String id = readString(buf);
                int memberCount = buf.readInt();
                if (id == null || memberCount <= 0 || memberCount > MAX_MEMBERS) {
                    return invalid();
                }
                totalMembers += memberCount;
                if (totalMembers > MAX_TOTAL_MEMBERS) {
                    return invalid();
                }
                List<String> members = new ArrayList<String>(memberCount);
                for (int j = 0; j < memberCount; j++) {
                    String member = readString(buf);
                    if (member == null) {
                        return invalid();
                    }
                    members.add(member);
                }
                groups.add(new RawGroup(id, members));
            }
            if (buf.isReadable()) {
                return invalid();
            }
            return new ObjectGroupWireConfig(version, revision, groups, true);
        } catch (IndexOutOfBoundsException e) {
            return invalid();
        }
    }

    /** 将 wire 值写出；生产调用方在发送前已通过 encodedSize 限制。 */
    public void write(ByteBuf buf) {
        if (!valid || encodedSize() > MAX_PAYLOAD_BYTES) {
            return;
        }
        buf.writeInt(protocolVersion);
        buf.writeLong(revision);
        buf.writeInt(groups.size());
        for (RawGroup group : groups) {
            writeString(buf, group.id);
            buf.writeInt(group.members.size());
            for (String member : group.members) {
                writeString(buf, member);
            }
        }
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public long revision() {
        return revision;
    }

    public List<RawGroup> groups() {
        return groups;
    }

    public boolean isValid() {
        return valid;
    }

    public int encodedSize() {
        int size = 4 + 8 + 4;
        for (RawGroup group : groups) {
            size += stringSize(group.id) + 4;
            for (String member : group.members) {
                size += stringSize(member);
            }
        }
        return size;
    }

    private static ObjectGroupWireConfig invalid() {
        return new ObjectGroupWireConfig(-1, -1L, Collections.<RawGroup>emptyList(), false);
    }

    private static int stringSize(String value) {
        return 2 + value.getBytes(UTF8).length;
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(UTF8);
        if (bytes.length > MAX_STRING_BYTES || bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("wire string exceeds limit");
        }
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return null;
        }
        int length = buf.readUnsignedShort();
        if (length > MAX_STRING_BYTES || buf.readableBytes() < length) {
            return null;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        CharsetDecoder decoder = UTF8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 有界原始组。 */
    public static final class RawGroup {
        private final String id;
        private final List<String> members;

        RawGroup(String id, List<String> members) {
            this.id = id;
            this.members = Collections.unmodifiableList(new ArrayList<String>(members));
        }

        public String id() {
            return id;
        }

        public List<String> members() {
            return members;
        }
    }
}
