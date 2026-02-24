package com.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Manifest 数据收集器
 * 用于收集生成 manifest.json 所需的各种元数据
 */
public class ManifestData {

    private final Set<String> orphanPipeNumbers = new LinkedHashSet<>();

    /**
     * 添加单体 |数字|
     *
     * @param number 数字字符串
     */
    public void addOrphanPipeNumber(String number) {
        if (number != null && !number.trim().isEmpty()) {
            orphanPipeNumbers.add(number.trim());
        }
    }

    /**
     * 批量添加单体 |数字|
     *
     * @param numbers 数字字符串列表
     */
    public void addOrphanPipeNumbers(List<String> numbers) {
        if (numbers != null) {
            for (String number : numbers) {
                addOrphanPipeNumber(number);
            }
        }
    }

    /**
     * 获取所有单体 |数字|
     *
     * @return 去重后的数字列表
     */
    public List<String> getOrphanPipeNumbers() {
        return new ArrayList<>(orphanPipeNumbers);
    }

    /**
     * 是否有单体 |数字|
     *
     * @return true 如果存在
     */
    public boolean hasOrphanPipeNumbers() {
        return !orphanPipeNumbers.isEmpty();
    }

    /**
     * 清空数据
     */
    public void clear() {
        orphanPipeNumbers.clear();
    }
}
