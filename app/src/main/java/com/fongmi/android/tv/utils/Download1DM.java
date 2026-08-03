package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import com.fongmi.android.tv.App;

import java.util.Map;

/**
 * 1DM+ 下载管理器集成工具类
 * 通过 Intent 调用 1DM+ (idm.internet.download.manager) 进行视频下载
 */
public class Download1DM {

    private static final String DOWNLOADER_CLASS = "idm.internet.download.manager.Downloader";

    private static final String[] PACKAGE_NAMES = {
            "idm.internet.download.manager.plus",
            "idm.internet.download.manager",
            "idm.internet.download.manager.adm.lite"
    };

    /**
     * 检查 1DM+ 是否已安装
     */
    public static boolean isInstalled() {
        PackageManager pm = App.get().getPackageManager();
        for (String pkg : PACKAGE_NAMES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 获取已安装的 1DM+ 包名
     */
    private static String getInstalledPackage() {
        PackageManager pm = App.get().getPackageManager();
        for (String pkg : PACKAGE_NAMES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /**
     * 发送下载请求到 1DM+
     * 使用 Application Context 和 FLAG_ACTIVITY_NEW_TASK，可从任意线程调用
     * 每次调用添加唯一时间戳，防止1DM+去重导致后续推送被吞掉
     *
     * @param url      下载地址
     * @param filename 文件名（可为空）
     * @param headers  HTTP 请求头（可为空）
     * @return true 表示成功发送 Intent
     */
    public static boolean sendDownload(String url, String filename, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return false;

        String packageName = getInstalledPackage();
        if (TextUtils.isEmpty(packageName)) {
            Notify.show("未安装 1DM+ 下载管理器");
            return false;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setComponent(new ComponentName(packageName, DOWNLOADER_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            if (Build.VERSION.SDK_INT >= 30) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
            }

            intent.putExtra("secure_uri", false);
            intent.setData(Uri.parse(url));

            // 添加唯一标识，防止1DM+去重导致后续推送被吞掉
            intent.putExtra("extra_unique_id", System.currentTimeMillis());
            intent.putExtra("extra_timestamp", System.nanoTime());

            if (!TextUtils.isEmpty(filename)) {
                intent.putExtra("extra_filename", filename);
            }

            if (headers != null && !headers.isEmpty()) {
                Bundle headersBundle = new Bundle();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        headersBundle.putString(entry.getKey(), entry.getValue());
                    }
                }
                intent.putExtra("extra_headers", headersBundle);
            }

            App.get().startActivity(intent);

            // 发送后等待短暂时间，确保1DM+ UI线程处理完当前Intent
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Notify.show("调用 1DM+ 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 兼容旧接口：从 Activity 调用
     */
    public static boolean sendDownload(Activity activity, String url, String filename, Map<String, String> headers) {
        return sendDownload(url, filename, headers);
    }
}
