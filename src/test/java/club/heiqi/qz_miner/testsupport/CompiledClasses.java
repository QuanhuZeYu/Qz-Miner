package club.heiqi.qz_miner.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.FieldNode;
import org.spongepowered.asm.lib.tree.InvokeDynamicInsnNode;
import org.spongepowered.asm.lib.tree.LdcInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.MultiANewArrayInsnNode;
import org.spongepowered.asm.lib.tree.TypeInsnNode;

import club.heiqi.qz_miner.MyMod;

/**
 * 生产编译产物的结构化读取工具：把「某类型是否被链接」「某方法是否被调用」「某注解是否挂在某方法上」
 * 变成对 {@code .class} 常量池与注解表的集合判定，替代对 Java 源码整文件做 {@code contains}。
 *
 * <p><b>为什么存在</b>：分侧边界、依赖方向、调用面清单这类契约，在源码层看是「谁 import 了谁」，
 * 一遇到等价重写、静态导入、字符串拼接就判不准；而编译产物里「常量池出现了这个类型 / 这个方法引用」
 * 是既成事实，与排版、局部变量名、注释无关。本类由两个并行批次的两份等价工具
 * （CompiledClasses / CompiledClassContent）收敛而来，是两者能力的并集：
 * 结构化读取（ASM ClassNode + 最小常量池解析器）与常量池文本子串读取并存——
 * 前者给集合，后者给「只要出现过就算」的快速否定式守卫。</p>
 *
 * <p><b>能证伪什么</b>：把被禁的类型 / 调用 / 字段真的写进实现（无论怎么排版、改什么局部变量名）；
 * 某个注解是否真的挂在类 / 方法 / 字段上，以及它的元素值（{@code @Mixin(value/targets/remap)}、
 * MixinExtras {@code @Definition} / {@code @Expression} 这类 <b>CLASS 保留</b>注解反射读不到，
 * 但就在产物的注解表里）；某方法是否真的接收该形参类型；某类型的直接超类与接口集合；
 * 某事件订阅方法的参数是不是事件基类的子类型；某类型在字节码里被实例化 / 被调用了几次、分别在哪个方法里
 * （{@link #instructionSites}：唯一实例化点、单一调用点这类唯一性契约）。</p>
 *
 * <p><b>守不到什么</b>：反射或字符串拼接出来的动态调用（常量池里只留下反射 API 名，看不到真实目标）；
 * 运行期的真实执行次数与顺序（这里只有「字节码里出现几次」）；控制流与数据流
 * （{@link #instructionSites} 给点位与计数，不回答「哪条分支会被走到」「这个值从哪来」）；
 * 注解元素的运行期语义（{@code @Mixin} 的目标是否真的被 Mixin 应用成功属真机 / Mixin AP 的事）；
 * 因此结构契约仍需源码切片工具（{@link JavaSourceSlices}）配合。</p>
 *
 * <p><b>服务用例</b>（client 域、chain.executor / chain.interaction 域与 mixins / toolswap 域）：
 * {@code AutoToolClientWiringStructureTest}、{@code BlockPickerEventRegistrationContractTest}、
 * {@code HudArchitectureBoundaryTest}、{@code KeyListenerMouseEventStructureTest}、
 * {@code PickerClassBoundaryTest}、{@code QzAutoToolSwapClientTransportStructureTest}、
 * {@code BlockInteractActionExecutorStructureTest}、{@code InteractionInventorySupportStructureTest}、
 * {@code LiquidSourceInteractActionExecutorStructureTest}、{@code ServerPlayerPoseTransactionStructureTest}、
 * {@code TargetRevalidatingBlockInteractActionExecutorStructureTest}、{@code InteractionRayTraceTest}、
 * {@code AutoToolSwapMixinStructureTest}、{@code LegacyNaturalMixinStructureTest}、
 * {@code ServerConfigurationManagerLifecycleMixinStructureTest}、{@code ChainPreviewVanillaHighlightMixinStructureTest}、
 * {@code AutoToolSwapRuntimeWiringStructureTest}、{@code PlayerManagerToolSwapLifecycleStructureTest}。</p>
 *
 * <p>常量池解析使用 JDK 自带的 {@link DataInputStream}（口径与仓内既有的 § 门禁解析器一致，
 * 只认 tag 结构，不依赖 ASM 版本）；注解表、类型层次与指令点位使用仓内已在用的 Mixin 内嵌 ASM
 * （{@code org.spongepowered.asm.lib}）；{@link #references(Class, String)} 则按类加载器资源取字节，
 * 与文件系统定位（{@link #root()}）互为补充。</p>
 *
 * <p>全部读取都只吃 {@code .class} 字节：<b>不加载被扫类型</b>（无 {@code Class.forName} / 无类初始化），
 * 因此 Minecraft 侧类型（{@code EntityPlayerMP}、{@code WorldServer} 等）可以安全读取字节做结构判定。</p>
 */
public final class CompiledClasses {

    /** 注解检索用的 {@code @SubscribeEvent} 描述符（RUNTIME 保留，会写进 RuntimeVisibleAnnotations）。 */
    public static final String SUBSCRIBE_EVENT = "Lcpw/mods/fml/common/eventhandler/SubscribeEvent;";

    /** Forge/FML 事件基类内部名：{@code @SubscribeEvent} 方法参数必须是它的子类型。 */
    public static final String EVENT_BASE = "cpw/mods/fml/common/eventhandler/Event";

    /** {@code @SideOnly} 注解描述符（带该注解的类型只在单侧加载，不属 common 面）。 */
    public static final String SIDE_ONLY = "Lcpw/mods/fml/relauncher/SideOnly;";

    private CompiledClasses() {
    }

    /** 生产 main 源集编译产物根目录（Gradle 默认布局，取自 {@link MyMod} 的 code source）。 */
    public static File root() {
        try {
            return new File(MyMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception failure) {
            throw new IllegalStateException("无法定位生产编译产物目录", failure);
        }
    }

    /** 全部生产 .class（按相对路径排序，保证扫描结果稳定）。 */
    public static List<File> classFiles() {
        List<File> files = new ArrayList<File>();
        collect(root(), files);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return relative(left).compareTo(relative(right));
            }
        });
        return files;
    }

    /** 相对产物根的 '/' 分隔路径，例如 {@code club/heiqi/qz_miner/ClientProxy.class}。 */
    public static String relative(File classFile) {
        return root().toURI().relativize(classFile.toURI()).getPath();
    }

    /** 该产物是否属客户端分侧（{@code client/} 段或根包 SidedProxy 客户端类）。 */
    public static boolean isClientSide(File classFile) {
        return relative(classFile).startsWith("client/") || relative(classFile).contains("/client/");
    }

    /** 读取类文件的编译产物字节。 */
    public static byte[] bytes(File classFile) throws IOException {
        Assert.assertTrue("编译产物必须存在（先跑 compileJava）: " + classFile.getPath(), classFile.isFile());
        return Files.readAllBytes(classFile.toPath());
    }

    /** 定位指定类型的编译产物（按内部名，例如 {@code club/heiqi/qz_miner/ClientProxy}）。 */
    public static File forInternalName(String internalName) {
        return new File(root(), internalName + ".class");
    }

    /**
     * 已编译类是否引用了该方法名/字段名/类型名（内部名形式，如 {@code net/minecraft/item/ItemStack}）。
     *
     * <p>与 {@link #classRefs(File)} 一类结构化读取的差别：本方法取<b>整个常量池的 ISO-8859-1 文本</b>
     * 做子串判定，因此不区分「类型引用」「成员引用」「字符串常量」；代价是小、快、不依赖 ASM，
     * 适合「绝不允许出现某名字」的否定式守卫。定位方式是<b>类加载器资源</b>
     * （{@code type.getName()} 换成斜杠路径），与 {@link #forInternalName(String)} 的文件系统定位不同，
     * 但指向同一份产物。</p>
     */
    public static boolean references(Class<?> type, String reference) {
        return constantPoolText(type).contains(reference);
    }

    /** 常量池文本；类文件缺失直接判失败（构建顺序错位时要吵，不要静默跳过）。 */
    private static String constantPoolText(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        Assert.assertNotNull("已编译类文件缺失: " + resource, in);
        try {
            return new String(readAll(in), "ISO-8859-1");
        } catch (IOException failure) {
            throw new AssertionError("读取已编译类失败: " + resource, failure);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 只读资源，关闭失败无后续影响
            }
        }
    }

    /** 常量池中出现的类型引用（CONSTANT_Class，数组已取元素类型）。 */
    public static Set<String> classRefs(File classFile) throws IOException {
        return refs(classFile).classRefs;
    }

    /** 常量池中出现的成员调用（{@code owner#name}，方法引用与接口方法引用）。 */
    public static Set<String> methodRefs(File classFile) throws IOException {
        return refs(classFile).methodRefs;
    }

    /** 常量池中出现的字段访问（{@code owner#name}）。 */
    public static Set<String> fieldRefs(File classFile) throws IOException {
        return refs(classFile).fieldRefs;
    }

    /** 常量池中的字符串常量（CONSTANT_String）。 */
    public static Set<String> stringConstants(File classFile) throws IOException {
        return refs(classFile).strings;
    }

    /** 全部常量池引用（一次解析，多处判定）。 */
    public static Refs refs(File classFile) throws IOException {
        return ConstantPool.read(bytes(classFile));
    }

    /** 直接超类内部名（无超类时为 null）。 */
    public static String superName(File classFile) throws IOException {
        return new ClassReader(bytes(classFile)).getSuperName();
    }

    /** 直接实现的接口内部名。 */
    public static List<String> interfaces(File classFile) throws IOException {
        String[] interfaces = new ClassReader(bytes(classFile)).getInterfaces();
        List<String> names = new ArrayList<String>();
        if (interfaces != null) {
            Collections.addAll(names, interfaces);
        }
        return names;
    }

    /** 类级注解描述符（RUNTIME 与 CLASS 保留都读，避免按保留级别漏判）。 */
    public static List<String> classAnnotations(File classFile) throws IOException {
        ClassNode node = readNode(classFile);
        return annotationTypes(node);
    }

    /**
     * 查找类级注解并读它的元素值（<b>RUNTIME 与 CLASS 保留通吃</b>）。
     *
     * <p><b>为什么存在</b>：{@code @Mixin(value/targets/remap)}、MixinExtras 的
     * {@code @Definition} / {@code @Expression} 都是 <b>CLASS 保留</b>注解——它们编译进产物的
     * {@code invisibleAnnotations} 里，反射读不到，而「目标类是谁、remap 开没开、注入的字段描述符
     * 是什么」恰恰是注入是否成立的真正契约。旧实现只扫 visibleAnnotations（实测对
     * {@code Lorg/spongepowered/asm/mixin/Mixin;} 恒为 null），等价替代只能退化成
     * 「注解描述符在不在 + 元素字面量在不在常量池里」两条弱判定。</p>
     *
     * <p><b>能证伪什么</b>：注解被删掉（返回 null）；元素被删掉或改名（{@link AnnotationInfo#has} 为 false）；
     * 元素值被改成别的类型 / 别的目标类（{@link AnnotationInfo#classNames} /
     * {@link AnnotationInfo#stringValues} / {@link AnnotationInfo#flag} 读出别的东西）；
     * {@code remap} 显式写成 false（{@code flag("remap")} 读回 Boolean.FALSE）。
     * 判定对象是产物注解表本身，与源码排版、常量池里恰好还有别的同名字符串无关。</p>
     *
     * <p><b>守不到什么</b>：注解的运行期语义（{@code @Mixin} 的目标是否真的被 Mixin 应用成功、
     * 注入点是否真的解析到 SRG 名——那是 Mixin AP 与真机的事）；元素里的嵌套注解会被
     * {@link AnnotationInfo#raw} 原样给出（不递归解析）；注解值的默认值（没写出来的元素不在表里，
     * 本类不做字节码默认值回填）；重复注解与参数注解（方法参数上的注解不在本类的读取面内）。</p>
     *
     * <p><b>服务哪些用例</b>：{@code AutoToolSwapMixinStructureTest}、
     * {@code LegacyNaturalMixinStructureTest}、{@code ServerConfigurationManagerLifecycleMixinStructureTest}、
     * {@code ChainPreviewVanillaHighlightMixinStructureTest}、{@code QzMinerMixinPluginTest}、
     * {@code PickerClassBoundaryTest}（{@code @SidedProxy} 字段元素）。</p>
     *
     * @return 注解信息；该注解不在类上时返回 null（调用方据此把「注解必须存在」显式钉住）
     */
    public static AnnotationInfo classAnnotation(File classFile, String annotationDescriptor) throws IOException {
        ClassNode node = readNode(classFile);
        return annotationOf(node.visibleAnnotations, node.invisibleAnnotations, annotationDescriptor);
    }

    /**
     * 按方法名查找方法级注解并读元素值（重载同名时取首个命中；RUNTIME 与 CLASS 保留通吃）。
     *
     * <p>四段说明同 {@link #classAnnotation(File, String)}：@Inject 是 RUNTIME 保留（反射也能读），
     * 而 MixinExtras 挂在 handler 方法上的 @Definition / @Expression 是 CLASS 保留，只能从这里读。</p>
     *
     * @return 注解信息；该方法不存在或没有该注解时返回 null
     */
    public static AnnotationInfo methodAnnotation(File classFile, String methodName, String annotationDescriptor)
            throws IOException {
        ClassNode node = readNode(classFile);
        for (MethodNode method : node.methods) {
            if (methodName.equals(method.name)) {
                AnnotationInfo annotation = annotationOf(method.visibleAnnotations, method.invisibleAnnotations,
                        annotationDescriptor);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /**
     * 按字段名查找字段级注解并读元素值（RUNTIME 与 CLASS 保留通吃）。
     *
     * <p>四段说明同 {@link #classAnnotation(File, String)}；与
     * {@link #annotatedFieldStringValue(File, String, String)} 的差别是「定位到指定字段」
     * 而不是「在任意字段上碰运气」，且给的是结构化元素值而非文本。</p>
     *
     * @return 注解信息；该字段不存在或没有该注解时返回 null
     */
    public static AnnotationInfo fieldAnnotation(File classFile, String fieldName, String annotationDescriptor)
            throws IOException {
        ClassNode node = readNode(classFile);
        for (FieldNode field : node.fields) {
            if (fieldName.equals(field.name)) {
                AnnotationInfo annotation = annotationOf(field.visibleAnnotations, field.invisibleAnnotations,
                        annotationDescriptor);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /**
     * 读类级注解的字符串元素（RUNTIME 与 CLASS 保留通吃；保留级别不影响命中）。
     *
     * @return 字符串标量元素值；注解或元素缺失、元素不是字符串时返回 null
     */
    public static String classAnnotationValue(File classFile, String annotationDescriptor, String elementName)
            throws IOException {
        AnnotationInfo annotation = classAnnotation(classFile, annotationDescriptor);
        return annotation == null ? null : annotation.stringValue(elementName);
    }

    /**
     * 在任意字段上查找注解并读元素文本（例如 {@code @SidedProxy(clientSide = "...")}，
     * 目标是字段而非类），不写死字段名。
     *
     * <p>与 {@link #fieldAnnotation(File, String, String)} 的差别：本方法不指定字段名，
     * 返回的是元素值的文本形式（{@code String.valueOf} 口径），供只关心「拿到了哪个字符串」的用例使用。</p>
     *
     * @return 元素值的文本形式；注解或元素缺失时返回 null
     */
    public static String annotatedFieldStringValue(File classFile, String annotationDescriptor, String elementName)
            throws IOException {
        ClassNode node = readNode(classFile);
        for (FieldNode field : node.fields) {
            AnnotationInfo annotation = annotationOf(field.visibleAnnotations, field.invisibleAnnotations,
                    annotationDescriptor);
            if (annotation != null) {
                Object value = annotation.raw(elementName);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        return null;
    }

    /** 在两组注解表（visible / invisible）里按描述符查找；保留级别不参与判定。 */
    private static AnnotationInfo annotationOf(List<AnnotationNode> visibleAnnotations,
            List<AnnotationNode> invisibleAnnotations, String descriptor) {
        AnnotationNode annotation = findAnnotation(visibleAnnotations, descriptor);
        if (annotation == null) {
            annotation = findAnnotation(invisibleAnnotations, descriptor);
        }
        return annotation == null ? null : new AnnotationInfo(annotation.desc, annotation.values);
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    /** 方法表（名字、描述符、注解描述符与元素值；RUNTIME 与 CLASS 保留都在内）。 */
    public static List<MethodInfo> methods(File classFile) throws IOException {
        ClassNode node = readNode(classFile);
        List<MethodInfo> methods = new ArrayList<MethodInfo>();
        for (MethodNode method : node.methods) {
            List<AnnotationNode> declared = new ArrayList<AnnotationNode>();
            if (method.visibleAnnotations != null) {
                declared.addAll(method.visibleAnnotations);
            }
            if (method.invisibleAnnotations != null) {
                declared.addAll(method.invisibleAnnotations);
            }
            List<String> annotations = new ArrayList<String>();
            List<AnnotationInfo> values = new ArrayList<AnnotationInfo>();
            for (AnnotationNode annotation : declared) {
                annotations.add(annotation.desc);
                values.add(new AnnotationInfo(annotation.desc, annotation.values));
            }
            methods.add(new MethodInfo(method.name, method.desc, annotations, values));
        }
        return methods;
    }

    /**
     * 方法体里的「引用类指令」点位（NEW / 类型指令 / 字段访问 / 方法调用 / 类字面量）。
     *
     * <p><b>为什么存在</b>：常量池只回答「有没有引用过」，回答不了「引用了几次、在哪个方法里」——
     * 而「这个服务只在一处被 new」「这个入口只有唯一调用点」「这条已删路径不得复活」这类唯一性契约，
     * 正是防重复接线、防第二真源的防线。{@code SKIP_CODE} 的口径（{@link #refs(File)}）看不到指令，
     * 反射数静态字段也证伪不了「同一字段被第二次 new 覆盖」。本方法直接读字节码指令序列。</p>
     *
     * <p><b>能证伪什么</b>：新增/删除一个实例化点或调用点（计数变化）；把实例化 / 调用挪到别的类型
     * （{@code owner} 变化）；同一类型被 new 两次（计数 2 ⇒ 唯一性断言红）；调用点从预期方法漂移到
     * 别的方法（{@link InstructionSite#method}）；已删除的旧闸门 / 旧回调重新被调用。
     * 判定对象是字节码点位，与源码排版、局部变量名、注释无关。</p>
     *
     * <p><b>守不到什么</b>：运行期真实执行次数与顺序（这里只有静态点位）；控制流可达性
     * （{@code if (false)} 里的 NEW 一样计入，本类不做常量传播）；反射 / {@code MethodHandle} 的
     * 真实目标；{@code INVOKEDYNAMIC} 的实现体（只有调用点名字与描述符，bootstrap 目标不在点位表里）；
     * 嵌套类各有独立指令序列，本方法只读传入的这一个产物（要覆盖「外层 + 嵌套」需逐个产物调用再取并集）。</p>
     *
     * <p><b>服务哪些用例</b>：{@code AutoToolSwapRuntimeWiringStructureTest}（唯一实例化点）、
     * {@code ChainExecutionEventBridgeTest}（旧闸门调用不得复活）、{@code ChainPreviewControllerTest}
     * （已删除的库存回退刷新不得复活）、{@code PlayerManagerToolSwapLifecycleStructureTest}
     * （回调只能从一条路径走）。</p>
     *
     * <p>读取只吃 {@code .class} 字节：不 {@code Class.forName}、不触发被扫类加载或初始化
     * （与 {@link #isSubtypeOf(String, String)} 同口径）。序号是方法体内的指令序号而不是字节码偏移，
     * 只用于区分同方法内的多个点位。</p>
     */
    public static List<InstructionSite> instructionSites(File classFile) throws IOException {
        return readInstructionSites(classFile);
    }

    /**
     * 按「操作码 + 目标 owner + 成员名」过滤指令点位；参数传 null 表示该项不限，
     * {@code name} 传空串表示只匹配「没有成员名」的类型指令。四段说明见 {@link #instructionSites(File)}。
     */
    public static List<InstructionSite> instructionSites(File classFile, String op, String owner, String name)
            throws IOException {
        List<InstructionSite> matched = new ArrayList<InstructionSite>();
        for (InstructionSite site : readInstructionSites(classFile)) {
            if (op != null && !op.equals(site.op)) {
                continue;
            }
            if (owner != null && !owner.equals(site.owner)) {
                continue;
            }
            if (name != null && !name.equals(site.name)) {
                continue;
            }
            matched.add(site);
        }
        return matched;
    }

    /** {@link #instructionSites(File, String, String, String)} 的计数形态（唯一性契约先看它）。 */
    public static int instructionCount(File classFile, String op, String owner, String name) throws IOException {
        return instructionSites(classFile, op, owner, name).size();
    }

    /**
     * 某类型在字节码里的全部实例化点（{@code NEW}）；「唯一实例化点」契约就是看它的长度。
     * 四段说明见 {@link #instructionSites(File)}。
     */
    public static List<InstructionSite> newSites(File classFile, String internalOwner) throws IOException {
        return instructionSites(classFile, "NEW", internalOwner, null);
    }

    /**
     * 某方法的全部调用点（静态 / 虚 / 特殊 / 接口调用都算，按「所属类型 + 方法名」定位）。
     * 四段说明见 {@link #instructionSites(File)}；{@code INVOKEDYNAMIC} 没有 owner，不参与匹配。
     */
    public static List<InstructionSite> callSites(File classFile, String owner, String name) throws IOException {
        List<InstructionSite> matched = new ArrayList<InstructionSite>();
        for (InstructionSite site : readInstructionSites(classFile)) {
            if (site.isCall() && owner.equals(site.owner) && name.equals(site.name)) {
                matched.add(site);
            }
        }
        return matched;
    }

    private static List<InstructionSite> readInstructionSites(File classFile) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(bytes(classFile)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<InstructionSite> sites = new ArrayList<InstructionSite>();
        for (MethodNode method : node.methods) {
            int index = 0;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                InstructionSite site = instructionSite(instruction, method, index);
                if (site != null) {
                    sites.add(site);
                }
                index++;
            }
        }
        return sites;
    }

    private static InstructionSite instructionSite(AbstractInsnNode instruction, MethodNode method, int index) {
        if (instruction instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode node = (MultiANewArrayInsnNode) instruction;
            return new InstructionSite("MULTIANEWARRAY", stripArray(node.desc), "", "dims=" + node.dims, method.name,
                    method.desc, index);
        }
        if (instruction instanceof TypeInsnNode) {
            TypeInsnNode node = (TypeInsnNode) instruction;
            String op = typeInstructionName(node.getOpcode());
            if (op == null) {
                return null;
            }
            return new InstructionSite(op, stripArray(node.desc), "", "", method.name, method.desc, index);
        }
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode node = (FieldInsnNode) instruction;
            String op = fieldInstructionName(node.getOpcode());
            if (op == null) {
                return null;
            }
            return new InstructionSite(op, node.owner, node.name, node.desc, method.name, method.desc, index);
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode node = (MethodInsnNode) instruction;
            String op = methodInstructionName(node.getOpcode());
            if (op == null) {
                return null;
            }
            return new InstructionSite(op, node.owner, node.name, node.desc, method.name, method.desc, index);
        }
        if (instruction instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode node = (InvokeDynamicInsnNode) instruction;
            return new InstructionSite("INVOKEDYNAMIC", "", node.name, node.desc, method.name, method.desc, index);
        }
        if (instruction instanceof LdcInsnNode) {
            Object constant = ((LdcInsnNode) instruction).cst;
            if (constant instanceof Type) {
                Type type = (Type) constant;
                if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                    return new InstructionSite("LDC", stripArray(type.getInternalName()), "", "", method.name,
                            method.desc, index);
                }
            }
        }
        return null;
    }

    private static String typeInstructionName(int opcode) {
        switch (opcode) {
            case Opcodes.NEW:
                return "NEW";
            case Opcodes.ANEWARRAY:
                return "ANEWARRAY";
            case Opcodes.CHECKCAST:
                return "CHECKCAST";
            case Opcodes.INSTANCEOF:
                return "INSTANCEOF";
            default:
                return null;
        }
    }

    private static String fieldInstructionName(int opcode) {
        switch (opcode) {
            case Opcodes.GETSTATIC:
                return "GETSTATIC";
            case Opcodes.PUTSTATIC:
                return "PUTSTATIC";
            case Opcodes.GETFIELD:
                return "GETFIELD";
            case Opcodes.PUTFIELD:
                return "PUTFIELD";
            default:
                return null;
        }
    }

    private static String methodInstructionName(int opcode) {
        switch (opcode) {
            case Opcodes.INVOKEVIRTUAL:
                return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL:
                return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC:
                return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE:
                return "INVOKEINTERFACE";
            default:
                return null;
        }
    }

    /**
     * {@code internalName} 是否（传递地）是 {@code expectedSuper} 的子类型。
     *
     * <p>用类加载器资源 + ASM 层次遍历判定，不触发任何类型初始化：Minecraft 侧事件类型
     * 在 headless 测试 JVM 里可以安全读取字节，不必加载。</p>
     */
    public static boolean isSubtypeOf(String internalName, String expectedSuper) throws IOException {
        Set<String> visited = new HashSet<String>();
        List<String> pending = new ArrayList<String>();
        pending.add(internalName);
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (current == null || current.isEmpty() || !visited.add(current)) {
                continue;
            }
            if (expectedSuper.equals(current)) {
                return true;
            }
            ClassReader reader = readerOf(current);
            if (reader == null) {
                continue;
            }
            pending.add(reader.getSuperName());
            String[] interfaces = reader.getInterfaces();
            if (interfaces != null) {
                for (String implemented : interfaces) {
                    pending.add(implemented);
                }
            }
        }
        return false;
    }

    /** 按内部名从类加载器读取类字节（缺失返回 null）。 */
    public static ClassReader readerOf(String internalName) throws IOException {
        InputStream input = CompiledClasses.class.getClassLoader()
                .getResourceAsStream(internalName + ".class");
        if (input == null) {
            return null;
        }
        try {
            return new ClassReader(readAll(input));
        } finally {
            input.close();
        }
    }

    /** 生产类型中所有带 {@code @SubscribeEvent} 的方法。 */
    public static List<SubscriberMethod> subscribeEventMethods() throws IOException {
        List<SubscriberMethod> found = new ArrayList<SubscriberMethod>();
        for (File classFile : classFiles()) {
            if (!relative(classFile).startsWith("club/heiqi/qz_miner/")) {
                continue;
            }
            for (MethodInfo method : methods(classFile)) {
                if (method.annotations.contains(SUBSCRIBE_EVENT)) {
                    found.add(new SubscriberMethod(relative(classFile), method));
                }
            }
        }
        return found;
    }

    private static List<String> annotationTypes(List<AnnotationNode> annotations) {
        List<String> types = new ArrayList<String>();
        if (annotations == null) {
            return types;
        }
        for (AnnotationNode annotation : annotations) {
            types.add(annotation.desc);
        }
        return types;
    }

    private static List<String> annotationTypes(ClassNode node) {
        List<String> types = annotationTypes(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) {
            for (AnnotationNode annotation : node.invisibleAnnotations) {
                types.add(annotation.desc);
            }
        }
        return types;
    }

    private static ClassNode readNode(File classFile) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(bytes(classFile)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    /** 读空一个输入流（类加载器资源与类文件字节共用；只读，不做任何解码）。 */
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull("编译产物目录不可读: " + file.getPath(), children);
            for (File child : children) {
                collect(child, files);
            }
            return;
        }
        if (file.getName().endsWith(".class")) {
            files.add(file);
        }
    }

    /**
     * 一个注解的「描述符 + 元素值表」（RUNTIME 与 CLASS 保留通吃）。
     *
     * <p><b>为什么存在</b>：CLASS 保留注解（{@code @Mixin} / MixinExtras {@code @Definition} /
     * {@code @Expression}）的元素值只能从产物字节里读；而 ASM 给出的元素值是弱类型对象
     * （{@code String} / {@code List} / {@link Type} / {@code Boolean} / 包装数值 / 嵌套注解节点），
     * 直接 {@code String.valueOf} 会把 {@code String[]} 打印成 {@code [Ljava.lang.String;@1a2b3c}
     * ——看着像读到了值，其实什么都没断言。本类把「按元素名定位」与「按标量 / 数组 / 类字面量 / 布尔
     * 归一」分开，让用例能写「{@code @Mixin} 的目标必须是这个类」，而不是「某串字符在不在常量池里」。</p>
     *
     * <p><b>能证伪什么</b>：元素缺失（{@link #has} 为 false）；写成别的标量（{@link #stringValue} 返回 null）；
     * 类目标从 A 换成 B（{@link #classNames} 给出别的名字）；把数组写成单值或反过来
     * （两者都按「清单」读，元素个数变化即可见）；{@code remap = false} 这类布尔开关被翻动
     * （{@link #flag} 读回另一个布尔）；元素类型写错（读成字符串时直接判失败，不给静默放行）。</p>
     *
     * <p><b>守不到什么</b>：注解是否被消费方（Mixin AP / 运行期框架）真的应用（这里只有产物元数据）；
     * 注解元素没写在源码里时的字节码默认值（表里没有就是没有，本类不做默认值回填）；
     * 嵌套注解的内部元素（{@link #raw} 原样给出，不递归）；枚举元素的语义
     * （ASM 给的是 {@code [描述符, 常量名]} 两元数组）；方法参数上的注解（不在本类的读取面内）。</p>
     *
     * <p><b>服务哪些用例</b>：{@code AutoToolSwapMixinStructureTest}、
     * {@code LegacyNaturalMixinStructureTest}、{@code ServerConfigurationManagerLifecycleMixinStructureTest}、
     * {@code ChainPreviewVanillaHighlightMixinStructureTest}。</p>
     */
    public static final class AnnotationInfo {

        /** 注解描述符（JVM 口径，如 {@code Lorg/spongepowered/asm/mixin/Mixin;}）。 */
        public final String descriptor;

        private final Map<String, Object> values;

        AnnotationInfo(String descriptor, List<Object> rawValues) {
            this.descriptor = descriptor;
            this.values = new LinkedHashMap<String, Object>();
            if (rawValues != null) {
                for (int index = 0; index + 1 < rawValues.size(); index += 2) {
                    Object name = rawValues.get(index);
                    if (name != null) {
                        this.values.put(String.valueOf(name), rawValues.get(index + 1));
                    }
                }
            }
        }

        /** 该元素是否出现在注解里（{@code @Mixin} 没写 {@code remap} 时它就不在表里）。 */
        public boolean has(String elementName) {
            return values.containsKey(elementName);
        }

        /** 元素的原始值（ASM 口径）；缺失返回 null。 */
        public Object raw(String elementName) {
            return values.get(elementName);
        }

        /** 字符串标量元素；缺失或不是字符串标量时返回 null。 */
        public String stringValue(String elementName) {
            Object value = values.get(elementName);
            return value instanceof String ? (String) value : null;
        }

        /** 字符串元素清单（标量按单元素处理，数组按原序展开）；出现非字符串元素即判失败。 */
        public List<String> stringValues(String elementName) {
            List<String> texts = new ArrayList<String>();
            for (Object value : elementsOf(elementName)) {
                if (!(value instanceof String)) {
                    throw new AssertionError(describe(elementName) + " 的元素不是字符串: " + value);
                }
                texts.add((String) value);
            }
            return texts;
        }

        /**
         * 类名字面量清单：{@code Class} 元素（ASM {@link Type}）取点分名，字符串元素按原样
         * （因此 {@code @Mixin(value = Foo.class)} 与 {@code @Mixin(targets = "a.b.Foo")} 都能读）；
         * 其它类型的元素直接判失败。
         */
        public List<String> classNames(String elementName) {
            List<String> names = new ArrayList<String>();
            for (Object value : elementsOf(elementName)) {
                if (value instanceof Type) {
                    names.add(((Type) value).getClassName());
                } else if (value instanceof String) {
                    names.add((String) value);
                } else {
                    throw new AssertionError(describe(elementName) + " 的元素不是类字面量或类名: " + value);
                }
            }
            return names;
        }

        /** 布尔元素（如 {@code @Mixin(remap = false)}）；缺失或不是布尔时返回 null。 */
        public Boolean flag(String elementName) {
            Object value = values.get(elementName);
            return value instanceof Boolean ? (Boolean) value : null;
        }

        private List<Object> elementsOf(String elementName) {
            Object value = values.get(elementName);
            if (value == null) {
                return new ArrayList<Object>();
            }
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                List<Object> elements = new ArrayList<Object>(list.size());
                elements.addAll(list);
                return elements;
            }
            List<Object> single = new ArrayList<Object>();
            single.add(value);
            return single;
        }

        private String describe(String elementName) {
            return descriptor + "#" + elementName;
        }

        @Override
        public String toString() {
            return descriptor + values;
        }
    }

    /** 一次解析得到的常量池引用集合。 */
    public static final class Refs {
        public final Set<String> classRefs = new LinkedHashSet<String>();
        public final Set<String> methodRefs = new LinkedHashSet<String>();
        public final Set<String> fieldRefs = new LinkedHashSet<String>();
        public final Set<String> strings = new LinkedHashSet<String>();

        /** 是否存在以给定前缀开头的类型引用（例如 UILib 内部实现包）。 */
        public boolean hasClassRefUnder(String internalNamePrefix) {
            for (String type : classRefs) {
                if (type.startsWith(internalNamePrefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 一个方法的名字、描述符与注解（描述符清单 + 元素值；RUNTIME 与 CLASS 保留都在内）。 */
    public static final class MethodInfo {
        public final String name;
        public final String descriptor;
        public final List<String> annotations;

        private final List<AnnotationInfo> annotationValues;

        MethodInfo(String name, String descriptor, List<String> annotations, List<AnnotationInfo> annotationValues) {
            this.name = name;
            this.descriptor = descriptor;
            this.annotations = annotations;
            this.annotationValues = annotationValues;
        }

        /** 该方法的某个注解的元素值（RUNTIME 与 CLASS 保留通吃）；没有该注解时返回 null。 */
        public AnnotationInfo annotation(String annotationDescriptor) {
            for (AnnotationInfo annotation : annotationValues) {
                if (annotation.descriptor.equals(annotationDescriptor)) {
                    return annotation;
                }
            }
            return null;
        }

        /** 实参类型的内部名（原始类型返回其名字，例如 {@code int}）。 */
        public List<String> parameterInternalNames() {
            List<String> names = new ArrayList<String>();
            for (Type type : Type.getArgumentTypes(descriptor)) {
                names.add(internalName(type));
            }
            return names;
        }

        private static String internalName(Type type) {
            if (type.getSort() == Type.ARRAY) {
                return internalName(type.getElementType());
            }
            if (type.getSort() == Type.OBJECT) {
                return type.getInternalName();
            }
            return type.getClassName();
        }

        @Override
        public String toString() {
            return name + descriptor;
        }
    }

    /**
     * 一条「引用类指令」的点位（操作码 + 目标 + 所在方法 + 方法内序号）。
     *
     * <p>四段说明见 {@link CompiledClasses#instructionSites(File)}；本类是它的返回元素，
     * 把「出现了几次」与「出现在哪个方法里」一起给出来，唯一性契约才既数得清也定位得到。</p>
     */
    public static final class InstructionSite {

        /**
         * 操作码助记符：{@code NEW} / {@code ANEWARRAY} / {@code CHECKCAST} / {@code INSTANCEOF} /
         * {@code MULTIANEWARRAY} / {@code GETSTATIC} / {@code PUTSTATIC} / {@code GETFIELD} / {@code PUTFIELD} /
         * {@code INVOKEVIRTUAL} / {@code INVOKESPECIAL} / {@code INVOKESTATIC} / {@code INVOKEINTERFACE} /
         * {@code INVOKEDYNAMIC} / {@code LDC}（类字面量）。
         */
        public final String op;

        /** 目标类型内部名（被实例化 / 被检查的类型、成员所属类型、类字面量类型）。 */
        public final String owner;

        /** 成员名（方法 / 字段）；类型指令与类字面量为空串。 */
        public final String name;

        /** 成员描述符（{@code MULTIANEWARRAY} 记 {@code dims=N}）；类型指令为空串。 */
        public final String descriptor;

        /** 所在方法名（构造器与静态初始化块原样保留 {@code <init>} / {@code <clinit>}）。 */
        public final String method;

        /** 所在方法描述符。 */
        public final String methodDescriptor;

        /** 该指令在所属方法体指令序列里的序号（0 起，不是字节码偏移；用于区分同方法内的多个点位）。 */
        public final int index;

        InstructionSite(String op, String owner, String name, String descriptor, String method,
                String methodDescriptor, int index) {
            this.op = op;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.method = method;
            this.methodDescriptor = methodDescriptor;
            this.index = index;
        }

        /** 是否是方法调用指令（{@code INVOKE*}）。 */
        public boolean isCall() {
            return op.startsWith("INVOKE");
        }

        /** {@code owner#name}（类型指令与类字面量只有 owner）。 */
        public String member() {
            return name.isEmpty() ? owner : owner + "#" + name;
        }

        /** 点位：{@code 方法名+描述符#序号}。 */
        public String location() {
            return method + methodDescriptor + "#" + index;
        }

        @Override
        public String toString() {
            return op + " " + member() + descriptor + " @" + location();
        }
    }

    /** 带 {@code @SubscribeEvent} 的方法及其所属产出。 */
    public static final class SubscriberMethod {
        public final String classPath;
        public final MethodInfo method;

        SubscriberMethod(String classPath, MethodInfo method) {
            this.classPath = classPath;
            this.method = method;
        }

        @Override
        public String toString() {
            return classPath + "#" + method;
        }
    }

    /** 最小 ClassFile 常量池读取器（只认 tag 结构；口径与仓内既有 § 门禁解析器一致）。 */
    private static final class ConstantPool {

        private ConstantPool() {
        }

        static Refs read(byte[] classBytes) throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
            if (input.readInt() != 0xcafebabe) {
                throw new IOException("invalid ClassFile magic");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            if (count == 0) {
                throw new IOException("invalid constant_pool_count");
            }
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            int[] fieldOwner = new int[count];
            int[] fieldNameAndType = new int[count];
            int[] methodOwner = new int[count];
            int[] methodNameAndType = new int[count];
            int[] stringIndex = new int[count];
            int[] nameAndTypeName = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[index] = input.readUTF();
                        break;
                    case 3:
                    case 4:
                        input.readInt();
                        break;
                    case 5:
                    case 6:
                        input.readLong();
                        index++;
                        if (index >= count) {
                            throw new IOException("long or double constant exceeds constant pool");
                        }
                        break;
                    case 7:
                        classNames[index] = input.readUnsignedShort();
                        break;
                    case 8:
                        stringIndex[index] = input.readUnsignedShort();
                        break;
                    case 9:
                        fieldOwner[index] = input.readUnsignedShort();
                        fieldNameAndType[index] = input.readUnsignedShort();
                        break;
                    case 10:
                    case 11:
                        methodOwner[index] = input.readUnsignedShort();
                        methodNameAndType[index] = input.readUnsignedShort();
                        break;
                    case 12:
                        nameAndTypeName[index] = input.readUnsignedShort();
                        input.readUnsignedShort();
                        break;
                    case 15:
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                        break;
                    case 16:
                    case 19:
                    case 20:
                        input.readUnsignedShort();
                        break;
                    case 17:
                    case 18:
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("unsupported constant pool tag: " + tag);
                }
            }
            Refs refs = new Refs();
            for (int index = 1; index < count; index++) {
                if (classNames[index] != 0) {
                    String name = utf8[classNames[index]];
                    if (name != null) {
                        refs.classRefs.add(stripArray(name));
                    }
                }
                if (stringIndex[index] != 0) {
                    String value = utf8[stringIndex[index]];
                    if (value != null) {
                        refs.strings.add(value);
                    }
                }
                if (fieldNameAndType[index] != 0) {
                    addMember(refs.fieldRefs, utf8, classNames[fieldOwner[index]],
                            utf8[nameAndTypeName[fieldNameAndType[index]]]);
                }
                if (methodNameAndType[index] != 0) {
                    addMember(refs.methodRefs, utf8, classNames[methodOwner[index]],
                            utf8[nameAndTypeName[methodNameAndType[index]]]);
                }
            }
            return refs;
        }

        private static void addMember(Set<String> target, String[] utf8, int classIndex, String memberName) {
            if (classIndex == 0 || memberName == null) {
                return;
            }
            String owner = utf8[classIndex];
            if (owner != null) {
                target.add(stripArray(owner) + "#" + memberName);
            }
        }

    }

    /** 去掉数组维度并拆掉 {@code L…;} 包装，得到元素类型的内部名（常量池与指令点位共用）。 */
    private static String stripArray(String internalName) {
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }
}
