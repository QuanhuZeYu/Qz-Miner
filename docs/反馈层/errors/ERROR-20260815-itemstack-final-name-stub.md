# ERROR-20260815-itemstack-final-name-stub.md

## 日期

2026-08-15

## 背景

`BlockVariantEnumerator.safeName` 本地化加固需要 4 条测试（正常翻译、displayName 未翻译回退、displayName 空、异常路径）。

## 问题

第一版测试想用 `ItemStack` 子类 stub `getDisplayName()`，编译报错：

```
无法从最终ItemStack进行继承
```

GTNH 1.7.10 的 `net.minecraft.item.ItemStack` 是 final 类，不能继承。

## 解决

沿用测试类既有「真实类 + 自定义子类」范式（如 `ExposingBlock`）：

- 用真实 `ItemStack(item, 1, 0)`（构造可用、`getItemDamage() == 0` 通过过滤）。
- 自定义 `Item` 子类覆盖 `getUnlocalizedName()`、`getUnlocalizedName(ItemStack)` 与
  `getItemStackDisplayName(ItemStack)` 控制 display 输出与异常。
- 自定义 `Block` 子类在 `getSubBlocks` 中注入该 item 的单 stack，经
  `BlockVariantEnumerator.enumerateBlock(registry, block, item)` 间接覆盖 `safeName` 分支。

注意 `ItemStack.getDisplayName()` 先调用 `item.getItemStackDisplayName(stack)` 再读
`stackTagCompound.display.Name`，因此异常与空 display 都必须从 Item 侧注入。
