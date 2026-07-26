package club.heiqi.qz_miner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.relauncher.Side;

/** 5.1 family 握手语法、矩阵与 Forge 接线回归。 */
public class QzMinerNetworkVersionPolicyTest {

    private static final String[] LEGAL_VERSIONS = {
            "5.1.0",
            "5.1.2147483647",
            "5.1.1-alpha",
            "5.1.2-alpha.1",
            "5.1.3-RC-1+build.0007",
            "5.1.0-ci+abcdef0123456789",
            "5.1.9-add-version-compatibility.17+abcdef12",
            "5.1.12-branch-name+abcdef12-dirty"
    };

    @Test
    public void legalStablePrereleaseBranchAndDirtyVersionsAreAccepted() {
        for (String version : LEGAL_VERSIONS) {
            Assert.assertTrue(version, QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.1.0-0"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.1.0+0001"));
        Assert.assertTrue(QzMinerNetworkVersionPolicy.isCompatibleFamily("5.1.0-a-b.C-D+0.00-x"));
    }

    @Test
    public void everyLegalPatchAndQualifierCombinationInteroperatesOnBothSides() {
        for (String local : LEGAL_VERSIONS) {
            for (String remote : LEGAL_VERSIONS) {
                Map<String, String> versions = singletonVersion(remote);
                Assert.assertTrue(local + " -> " + remote + " CLIENT",
                        QzMinerNetworkVersionPolicy.accepts(local, versions, MyMod.MODID, Side.CLIENT));
                Assert.assertTrue(local + " -> " + remote + " SERVER",
                        QzMinerNetworkVersionPolicy.accepts(local, versions, MyMod.MODID, Side.SERVER));
            }
        }
    }

    @Test
    public void otherFamiliesAndMalformedVersionsAreRejectedWithoutNormalization() {
        String[] rejected = {
                "",
                "5.0.24",
                "5.10.0",
                "4.1.0",
                "5.1",
                "5.1.0.1",
                "05.1.0",
                "5.01.0",
                "5.1.00",
                "2147483648.1.0",
                "5.2147483648.0",
                "5.1.2147483648",
                "5.1.-1",
                "5.1.0-",
                "5.1.0+",
                "5.1.0-alpha.",
                "5.1.0-alpha..1",
                "5.1.0-01",
                "5.1.0-alpha.01",
                "5.1.0+build.",
                "5.1.0+build..1",
                "5.1.0-alpha_beta",
                "5.1.0+build_beta",
                "5.1.0-alpha+build+extra",
                "5.1.0-α",
                "5.1.0+构建",
                "x5.1.0",
                "5.1.0x",
                " 5.1.0",
                "5.1.0 ",
                "5.1.0\n"
        };
        Assert.assertFalse(QzMinerNetworkVersionPolicy.isCompatibleFamily(null));
        for (String version : rejected) {
            Assert.assertFalse(version, QzMinerNetworkVersionPolicy.isCompatibleFamily(version));
        }
    }

    @Test
    public void missingRemoteModIsAllowedButNullEnvelopeAndPresentBadValuesFailClosed() {
        for (Side side : new Side[] {Side.CLIENT, Side.SERVER}) {
            Assert.assertTrue(QzMinerNetworkVersionPolicy.accepts(
                    null, Collections.<String, String>emptyMap(), MyMod.MODID, side));
            Assert.assertTrue(QzMinerNetworkVersionPolicy.accepts(
                    "not-a-version", Collections.singletonMap("other_mod", "1.0.0"), MyMod.MODID, side));
        }

        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.1.0", null, MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.1.0", Collections.<String, String>emptyMap(), null, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.1.0", Collections.<String, String>emptyMap(), MyMod.MODID, null));

        Map<String, String> nullVersion = singletonVersion(null);
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts("5.1.0", nullVersion, MyMod.MODID, Side.SERVER));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.1.0", singletonVersion(""), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                null, singletonVersion("5.1.0"), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.0.24", singletonVersion("5.1.0"), MyMod.MODID, Side.CLIENT));
        Assert.assertFalse(QzMinerNetworkVersionPolicy.accepts(
                "5.1.0", singletonVersion("5.0.24"), MyMod.MODID, Side.SERVER));
    }

    @Test
    public void myModHasOneUnparameterizedHandlerThatOnlyDelegatesToPolicy() throws Exception {
        String allMainSources = readJavaTree(new File("src/main/java"));
        String myMod = read(new File("src/main/java/club/heiqi/qz_miner/MyMod.java"));
        String normalized = myMod.replaceAll("\\s+", " ");

        Assert.assertEquals(1, count(allMainSources, "@NetworkCheckHandler"));
        Assert.assertTrue(normalized.contains(
                "@NetworkCheckHandler public boolean checkNetworkVersions("
                + "Map<String, String> remoteVersions, Side side) { "
                + "return QzMinerNetworkVersionPolicy.accepts(Tags.VERSION, remoteVersions, MODID, side); }"));
        Assert.assertFalse(myMod.contains("@NetworkCheckHandler("));
        Assert.assertFalse(allMainSources.contains("acceptableRemoteVersions"));
        Assert.assertFalse(read(new File(
                "src/main/java/club/heiqi/qz_miner/QzMinerNetworkVersionPolicy.java")).contains("startsWith("));
    }

    private static Map<String, String> singletonVersion(String version) {
        Map<String, String> versions = new HashMap<String, String>();
        versions.put(MyMod.MODID, version);
        return versions;
    }

    private static String readJavaTree(File root) throws Exception {
        StringBuilder result = new StringBuilder();
        File[] children = root.listFiles();
        if (children == null) {
            return result.toString();
        }
        for (File child : children) {
            if (child.isDirectory()) {
                result.append(readJavaTree(child));
            } else if (child.getName().endsWith(".java")) {
                result.append(read(child)).append('\n');
            }
        }
        return result.toString();
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }
}
