package com.nless.mypdf.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartVolumeSniffer {

    /**
     * 核心调度：在同级目录下，找出当前文件的“下一卷”
     *
     * @param currentFileName 当前正在阅读的文件名 (例如 "001：第1话-xx.pdf")
     * @param allSiblings     当前文件夹下所有 PDF 文件的名字集合
     * @return 下一卷的文件名。如果已经是最后一卷，或没有匹配规则的文件，返回 null。
     */
    public static String findNextVolume(String currentFileName, List<String> allSiblings) {
        if (currentFileName == null || allSiblings == null || allSiblings.isEmpty()) {
            return null;
        }

        // 1. 获取当前文件的“特征指纹”
        String targetSignature = getPatternSignature(currentFileName);

        // 2. 聚类：筛选出所有符合该指纹（同一个系列）的文件
        List<String> sameSeriesFiles = new ArrayList<>();
        for (String sibling : allSiblings) {
            if (getPatternSignature(sibling).equals(targetSignature)) {
                sameSeriesFiles.add(sibling);
            }
        }

        // 3. 如果这个系列只有它自己一本，直接返回
        if (sameSeriesFiles.size() <= 1) {
            return null;
        }

        // 4. 对这个系列进行“自然语义排序” (1, 2, 3... 10... 11)
        Collections.sort(sameSeriesFiles, new NaturalComparator());

        // 5. 找到当前文件所在的位置，抓取下一本
        int currentIndex = sameSeriesFiles.indexOf(currentFileName);
        if (currentIndex >= 0 && currentIndex < sameSeriesFiles.size() - 1) {
            return sameSeriesFiles.get(currentIndex + 1);
        }

        return null; // 已经是该系列的最后一本了
    }

    /**
     * 提取特征指纹：将所有连续的数字替换为 "#"
     * 例子： "001：第1话-xx.pdf" -> "#：第#话-xx.pdf"
     */
    private static String getPatternSignature(String fileName) {
        if (fileName == null) return "";
        // 正则：匹配一段或多段连续的数字
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(fileName.toLowerCase());
        // 将数字全部抹平为 "#"
        return matcher.replaceAll("#");
    }

    /**
     * 自然语义比较器（像人类一样排序：2 会排在 10 前面）
     */
    public static class NaturalComparator implements Comparator<String> {

        private boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private String getChunk(String s, int slength, int marker) {
            StringBuilder chunk = new StringBuilder();
            char c = s.charAt(marker);
            chunk.append(c);
            marker++;
            if (isDigit(c)) {
                while (marker < slength) {
                    c = s.charAt(marker);
                    if (!isDigit(c)) break;
                    chunk.append(c);
                    marker++;
                }
            } else {
                while (marker < slength) {
                    c = s.charAt(marker);
                    if (isDigit(c)) break;
                    chunk.append(c);
                    marker++;
                }
            }
            return chunk.toString();
        }

        @Override
        public int compare(String s1, String s2) {
            int thisMarker = 0;
            int thatMarker = 0;
            int s1Length = s1.length();
            int s2Length = s2.length();

            while (thisMarker < s1Length && thatMarker < s2Length) {
                String thisChunk = getChunk(s1, s1Length, thisMarker);
                thisMarker += thisChunk.length();

                String thatChunk = getChunk(s2, s2Length, thatMarker);
                thatMarker += thatChunk.length();

                int result = 0;
                // 如果两块都是数字，进行数值比较
                if (isDigit(thisChunk.charAt(0)) && isDigit(thatChunk.charAt(0))) {
                    int thisZeros = 0;
                    while (thisZeros < thisChunk.length() && thisChunk.charAt(thisZeros) == '0') thisZeros++;
                    int thatZeros = 0;
                    while (thatZeros < thatChunk.length() && thatChunk.charAt(thatZeros) == '0') thatZeros++;

                    int thisRemLen = thisChunk.length() - thisZeros;
                    int thatRemLen = thatChunk.length() - thatZeros;

                    if (thisRemLen < thatRemLen) result = -1;
                    else if (thisRemLen > thatRemLen) result = 1;
                    else {
                        result = thisChunk.substring(thisZeros).compareTo(thatChunk.substring(thatZeros));
                    }
                    if (result == 0) {
                        result = thisChunk.length() - thatChunk.length(); // 长度相同的 01 和 1，让短的排前面
                    }
                } else {
                    // 如果是普通字符串，直接比对
                    result = thisChunk.compareTo(thatChunk);
                }

                if (result != 0) return result;
            }
            return s1Length - s2Length;
        }
    }
}