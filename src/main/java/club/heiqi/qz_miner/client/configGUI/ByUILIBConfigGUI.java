package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_uilib.gui.ConfigGuiTemplate;
import club.heiqi.qz_uilib.widget.*;
import club.heiqi.qz_uilib.widget.layout.HorizontalLayout;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ByUILIBConfigGUI extends ConfigGuiTemplate {

    public ByUILIBConfigGUI(GuiScreen parent) {
        super(parent);
    }

    @Override
    public void saveConfigCallback() {
        MyMod.config.load();
        Config.config.save();
    }

    @Override
    public List<ConfigCategory> getCategory() {
        ArrayList<ConfigCategory> results = new ArrayList<>();

        ConfigCategory general = Config.config.getCategory(Configuration.CATEGORY_GENERAL);
        ConfigCategory client = Config.config.getCategory(Config.CLIENT_CATEGORY.toLowerCase());

        results.add(general);
        results.add(client);

        return results;
    }

    @Override
    public ListWidget createConfigList() {
        List<ConfigCategory> categories = this.getCategory();
        ListWidget configList = new ListWidget();

        for(ConfigCategory category : categories) {
            String categoryName = category.getName();
            LabelWidget categoryLabel = (new LabelWidget()).setText(categoryName);
            categoryLabel.setPerfectSize(-1.0F, 64.0F);

            for(Map.Entry<String, Property> entry : category.entrySet()) {
                String title = (String)entry.getKey();
                Property property = (Property)entry.getValue();
                LabelWidget titleLabel = (new LabelWidget()).setText(title);
                titleLabel.setTooltip(property.comment);
                Property.Type type = property.getType();
                Widget valueWidget = new Widget();
                switch (type) {
                    case INTEGER:
                        if (!property.isList()) {
                            int initValue = property.getInt();

                            IntegerEditWidget edit = new IntegerEditWidget();
                            edit.content = String.valueOf(initValue);
                            edit.setPerfectSize(-1.0F, 32.0F + edit.insideMargins * 2.0F);
                            Consumer<String> onTextChange = (value) -> {
                                int intValue = edit.getIntValue();
                                if (intValue >= Integer.parseInt(property.getMinValue()) && intValue <= Integer.parseInt(property.getMaxValue())) {
                                    this.saveOperators.put(title, (Runnable)() -> property.set(intValue));
                                }

                            };
                            edit.setTextChangeCallBack(onTextChange);

                            IntegerSliderWidget sliderWidget = new IntegerSliderWidget();
                            int maxValue = Integer.parseInt(property.getMaxValue());
                            if (title.equals("bigRadius")) maxValue = 128;
                            else if (title.equals("blockLimit")) maxValue = 512000;
                            else if (title.equals("smallRadius")) maxValue = 8;
                            else if (title.equals("tunnelWidth")) maxValue = 16;
                            sliderWidget.setRange(Integer.parseInt(property.getMinValue()), maxValue);
                            sliderWidget.setSliderChangeCallBack((integer) -> {
                                edit.setContent(integer.toString());
                                this.saveOperators.put(title, (Runnable)() -> property.set(integer));
                            });
                            edit.perfectWidth = sliderWidget.perfectWidth = -1;

                            valueWidget.setLayout(new HorizontalLayout());
                            valueWidget.addChild(edit);
                            valueWidget.addChild(sliderWidget);
                            valueWidget.setPerfectHeight(Arrays.asList(edit, sliderWidget));
                        }
                        break;
                    case BOOLEAN:
                        if (!property.isList()) {
                            boolean initValue = property.getBoolean();
                            ButtonWithTextWidget edit = new ButtonWithTextWidget();
                            edit.setText(String.valueOf(initValue)).setTextColor((Integer)this.boolColorMap.get(initValue));
                            edit.setPerfectSize(-1.0F, 32.0F + edit.insideMargins * 2.0F);
                            valueWidget = edit;
                            edit.setCallBack(() -> {
                                boolean setValue = false;
                                if (edit.text.equalsIgnoreCase("false")) {
                                    setValue = true;
                                } else {
                                    setValue = false;
                                }

                                edit.setText(String.valueOf(setValue)).setTextColor(this.boolColorMap.get(setValue));
                                edit.perfectWidth = -1.0F;
                                boolean finalSetValue = setValue;
                                this.saveOperators.put(title, () -> property.set(finalSetValue));
                            });
                        }
                        break;
                    case STRING:
                        if (!property.isList()) {
                            String initValue = property.getString();
                            TextEditWidget edit = new TextEditWidget();
                            edit.setContent(initValue);
                            edit.setPerfectSize(-1.0F, 32.0F + edit.insideMargins * 2.0F);
                            valueWidget = edit;
                            Consumer<String> onTextChange = (value) -> this.saveOperators.put(title, () -> property.set(value));
                            edit.setTextChangeCallBack(onTextChange);
                        }
                        break;
                    case DOUBLE:
                        double initValue = property.getDouble();
                        DoubleEditWidget edit = new DoubleEditWidget();
                        edit.setContent(String.valueOf(initValue));
                        edit.setPerfectSize(-1.0F, 32.0F + edit.insideMargins * 2.0F);
                        valueWidget = edit;
                        Consumer<String> onTextChange = (value) -> {
                            double doubleValue = edit.getDoubleValue();
                            if (doubleValue >= Double.parseDouble(property.getMinValue()) && doubleValue <= Double.parseDouble(property.getMaxValue())) {
                                this.saveOperators.put(title, () -> property.set(doubleValue));
                            }

                        };
                        edit.setTextChangeCallBack(onTextChange);
                }

                titleLabel.perfectWidth = valueWidget.perfectWidth = -1.0F;
                Widget hW = (new Widget()).setLayout(new HorizontalLayout());
                hW.addChild(titleLabel);
                hW.addChild(valueWidget);
                hW.setPerfectHeight(Arrays.asList(titleLabel, valueWidget));
                configList.addChild(hW);
            }
        }

        return configList;
    }
}
