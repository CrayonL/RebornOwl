package com.owl.auxiliary;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.FileOutputStream;
import org.xmlpull.v1.XmlSerializer;

/** 读取真实无障碍树；避开系统 dump 在宿主持续动画下的 idle 等待超时。 */
public final class UiDump {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = type.getConstructor().newInstance();
        type.getMethod("connect").invoke(wrapper);
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            UiAutomation automation = (UiAutomation) type.getMethod("getUiAutomation").invoke(wrapper);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) { throw new IllegalStateException("当前窗口无可读的无障碍树"); }
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(out, "UTF-8");
            xml.startDocument("UTF-8", true);
            xml.startTag(null, "hierarchy");
            dump(root, xml);
            xml.endTag(null, "hierarchy");
            xml.endDocument();
        } finally {
            type.getMethod("disconnect").invoke(wrapper);
        }
    }

    private static void dump(AccessibilityNodeInfo node, XmlSerializer xml) throws Exception {
        if (!node.isVisibleToUser()) { return; }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "text", value(node.getText()));
        xml.attribute(null, "content-desc", value(node.getContentDescription()));
        xml.attribute(null, "package", value(node.getPackageName()));
        xml.attribute(null, "class", value(node.getClassName()));
        xml.attribute(null, "clickable", Boolean.toString(node.isClickable()));
        xml.attribute(null, "checked", Boolean.toString(node.isChecked()));
        xml.attribute(null, "enabled", Boolean.toString(node.isEnabled()));
        xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { dump(child, xml); }
        }
        xml.endTag(null, "node");
    }

    private static String value(CharSequence text) {
        return text == null ? "" : text.toString();
    }
}
