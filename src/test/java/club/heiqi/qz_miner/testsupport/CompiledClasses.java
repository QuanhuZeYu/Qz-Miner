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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;

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
 * 某个注解是否真的挂在类 / 方法 / 字段上；某方法是否真的接收该形参类型；某类型的直接超类与接口集合；
 * 某事件订阅方法的参数是不是事件基类的子类型。</p>
 *
 * <p><b>守不到什么</b>：反射或字符串拼接出来的动态调用（常量池里只留下反射 API 名）；
 * 运行期的真实调用次数与顺序（这里只有「引用存在」）；被 {@code SKIP_CODE} 跳过的指令细节
 * （方法体内部的控制流一律看不见，本类只读常量池、注解表与类型层次），
 * 因此结构契约仍需源码切片工具（{@link JavaSourceSlices}）配合。</p>
 *
 * <p><b>服务用例</b>（client 域与 chain.executor / chain.interaction 域）：
 * {@code AutoToolClientWiringStructureTest}、{@code BlockPickerEventRegistrationContractTest}、
 * {@code HudArchitectureBoundaryTest}、{@code KeyListenerMouseEventStructureTest}、
 * {@code PickerClassBoundaryTest}、{@code QzAutoToolSwapClientTransportStructureTest}、
 * {@code BlockInteractActionExecutorStructureTest}、{@code InteractionInventorySupportStructureTest}、
 * {@code LiquidSourceInteractActionExecutorStructureTest}、{@code ServerPlayerPoseTransactionStructureTest}、
 * {@code TargetRevalidatingBlockInteractActionExecutorStructureTest}、{@code InteractionRayTraceTest}。</p>
 *
 * <p>常量池解析使用 JDK 自带的 {@link DataInputStream}（口径与仓内既有的 § 门禁解析器一致，
 * 只认 tag 结构，不依赖 ASM 版本）；注解表与类型层次使用仓内已在用的 Mixin 内嵌 ASM
 * （{@code org.spongepowered.asm.lib}）；{@link #references(Class, String)} 则按类加载器资源取字节，
 * 与文件系统定位（{@link #root()}）互为补充。</p>
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
     * 读类级注解的字符串元素。
     *
     * <p>注解保留级别可能是 CLASS（反射不可见），因此这里读编译产物本身而不是 {@code getAnnotation}。</p>
     *
     * @return 元素值；注解或元素缺失时返回 null
     */
    public static String classAnnotationValue(File classFile, String annotationDescriptor, String elementName)
            throws IOException {
        ClassNode node = readNode(classFile);
        return annotationValue(findAnnotation(node.visibleAnnotations, annotationDescriptor), elementName);
    }

    /**
     * 在任意字段上查找注解并读字符串元素（例如 {@code @SidedProxy(clientSide = "...")}，
     * 目标是字段而非类），不写死字段名。
     *
     * @return 元素值；注解或元素缺失时返回 null
     */
    public static String annotatedFieldStringValue(File classFile, String annotationDescriptor, String elementName)
            throws IOException {
        ClassNode node = readNode(classFile);
        for (org.spongepowered.asm.lib.tree.FieldNode field : node.fields) {
            AnnotationNode annotation = findAnnotation(field.visibleAnnotations, annotationDescriptor);
            if (annotation == null) {
                annotation = findAnnotation(field.invisibleAnnotations, annotationDescriptor);
            }
            String value = annotationValue(annotation, elementName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String annotationValue(AnnotationNode annotation, String elementName) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (elementName.equals(annotation.values.get(index))) {
                Object value = annotation.values.get(index + 1);
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
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

    /** 方法表（名字、描述符、可见注解描述符）。 */
    public static List<MethodInfo> methods(File classFile) throws IOException {
        ClassNode node = readNode(classFile);
        List<MethodInfo> methods = new ArrayList<MethodInfo>();
        for (MethodNode method : node.methods) {
            List<String> annotations = new ArrayList<String>();
            annotations.addAll(annotationTypes(method.visibleAnnotations == null
                    ? null : method.visibleAnnotations));
            if (method.invisibleAnnotations != null) {
                for (AnnotationNode annotation : method.invisibleAnnotations) {
                    annotations.add(annotation.desc);
                }
            }
            methods.add(new MethodInfo(method.name, method.desc, annotations));
        }
        return methods;
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

    /** 一个方法的名字、描述符与可见注解描述符。 */
    public static final class MethodInfo {
        public final String name;
        public final String descriptor;
        public final List<String> annotations;

        MethodInfo(String name, String descriptor, List<String> annotations) {
            this.name = name;
            this.descriptor = descriptor;
            this.annotations = annotations;
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
}
