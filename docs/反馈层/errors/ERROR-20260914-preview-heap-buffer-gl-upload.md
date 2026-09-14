# ERROR-20260914 heap ByteBuffer 传给 GL 上传导致真机驱动崩溃

## 现象

T51 方向流 byte 化后首次实机：shader 后端正常启用（`backend in use: id=shader`），
随即整进程崩溃：

```
#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff96de28b9d
# Problematic frame:
# C  [nvoglv64.dll+0x15b8b9d]

J  org.lwjgl.opengl.GL15C.nglBufferSubData(IJJJ)V
j  org.lwjgl.opengl.GL15C.glBufferSubData(IJLjava/nio/ByteBuffer;)V
j  ...angelica.glsm.GLStateManager.glBufferSubData(IJLjava/nio/ByteBuffer;)V
j  ChainPreviewShaderBackend.uploadDirections([BI)V+84
j  ChainPreviewShaderBackend.uploadTopology(...)
```

崩在 native 层，**没有任何 Java 异常**可看，只有 hs_err 里的 native + Java 栈能定位。

## 根因

新写的方向 staging 用了 **heap buffer**：

```java
buffer = ByteBuffer.allocate(calculateElementCapacity(required));   // heap
```

而 GL 上传只接受 **direct buffer**。本仓既有路径统一走注入的分配器：

```java
interface BufferAllocator { ByteBuffer byteBuffer(int capacity); }
// 默认实现 -> BufferUtils.createByteBuffer(capacity)   // direct
```

heap ByteBuffer 有 backing array、没有 native 地址，`glBufferSubData` 取地址时拿到非法值，
驱动读越界 → ACCESS_VIOLATION。

同源隐患还有既有的 `prepareUndefinedBuffer`（无语义流时的 aAux 填充），同样用 heap。

## 为什么全套测试全绿

**单元测试注入的是纯 JVM 分配器**（headless 不加载 LWJGL native）：

```java
HEAP_ALLOCATOR -> java.nio.ByteBuffer.allocate(capacity)
```

在模拟 GL 下 heap buffer 完全正常，`isDirect()` 也从来没被检查过。于是
**测试替身把「真机才成立的前提」悄悄抹掉了**——这条路径一路绿到真机才炸。

这是本次最值钱的教训：**凡是被测试替身替换掉的资源，其真实约束必须另外钉住**，
不能依赖走替身的那条测试路径。

## 修法

1. 方向 staging 两处、`prepareUndefinedBuffer` 的分配来源全部改为显式传入的
   `allocator`——**分配来源变成方法签名的一部分**，不再有"默认走 heap"的暗路。
2. 新增运行时守卫 `requireDirect`：上传前检查，把"崩在驱动里"变成"Java 层抛异常"。
   它**只约束真机路径**（`allocator == LWJGL_ALLOCATOR`）；测试替身豁免，否则 headless 测试无法运行。
3. 新增源码级防线 `backendStagingNeverUsesHeapByteBuffer`：断言后端源码里不出现
   `ByteBuffer.allocate(`。因为第 1、2 条都在测试路径上豁免，只有源码级检查能覆盖
   "未来又有人直接 new 一个 heap buffer"。

## 通用判据

**GL 上传的 staging buffer 必须来自 direct 分配器**；在 core profile 环境里，
heap buffer 不是"效率差一点"，而是**进程级崩溃**。新增任何上传流时，
先确认缓冲来自 `allocator`。
