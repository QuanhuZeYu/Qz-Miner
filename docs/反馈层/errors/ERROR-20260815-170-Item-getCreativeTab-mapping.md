# ERROR-20260815-170-Item-getCreativeTab-mapping.md

## 现象

接入创造物品栏式 picker 时，枚举器调用 `Item.getCreativeTabToDisplayOn()` 编译失败：

```
错误: 找不到符号 CreativeTabs tab = item.getCreativeTabToDisplayOn();
  符号:   方法 getCreativeTabToDisplayOn()
  位置: 类型为Item的变量 item
```

## 根因

MCP 9.03（1.7.10 GTNH 基线）的命名与直觉相反：

- `net.minecraft.item.Item` 的方法是 **`getCreativeTab()`**（srg `func_77640_w`，"gets the CreativeTab this item is displayed on"，返回 `tabToDisplayOn`，普通 Item 可为 null）；
- `net.minecraft.block.Block` 的方法才是 **`getCreativeTabToDisplayOn()`**（srg `func_149708_J`）；
- `ItemBlock.getCreativeTab()` override 返回 `block.getCreativeTabToDisplayOn()`，即方块注册的创造栏。

## 处置

`BlockVariantEnumerator.creativeTabLabelOf(Item)` 统一改用 `item.getCreativeTab()`，ItemBlock 路径经 override 自然落到方块创造栏；`tab == CreativeTabs.tabAllSearch || tab == null` 判定放在 `getTranslatedTabLabel()` 之前，避免触碰搜索页特殊实例的本地化。

## 校验方式

本地 `conf/methods.csv` + `conf/packaged.srg` 核对映射后再写调用点：

```
func_77640_w,getCreativeTab,0,gets the CreativeTab this item is displayed on
func_149708_J,getCreativeTabToDisplayOn,0,Returns the CreativeTab to display the given block on.
MD: adb/n_ ()Labt; net/minecraft/item/Item/func_77640_w ()Lnet/minecraft/creativetab/CreativeTabs;
MD: aji/J ()Labt; net/minecraft/block/Block/func_149708_J ()Lnet/minecraft/creativetab/CreativeTabs;
```

## 经验

写 1.7.10 创造栏相关代码前，先用 `conf/methods.csv` 反查 MCP 名归属的类；同名语义方法可能分属 Item/Block 两个类。
