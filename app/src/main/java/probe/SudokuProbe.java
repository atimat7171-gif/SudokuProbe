package probe;

import android.app.AndroidAppHelper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Names come from classes12.dex of Sudoku 5.86.0 base.apk.
// row/col base (0 or 1) and whether answerNum is filled at runtime are NOT confirmed.
public class SudokuProbe implements IXposedHookLoadPackage {
    private static final String PKG = "easy.sudoku.puzzle.solver.free";
    private static final String CELL = "com.meevii.data.bean.CellData";
    private static final String GAME = "com.meevii.data.bean.GameData";
    private static final int MAX_CELLS = 81;

    private static volatile List<?> lastCells;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        try {
            Class<?> game = XposedHelpers.findClass(GAME, lp.classLoader);
            Class<?> cell = XposedHelpers.findClass(CELL, lp.classLoader);

            XposedBridge.hookAllMethods(game, "getCellDataList", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getResult() instanceof List) lastCells = (List<?>) p.getResult();
                }
            });

            XposedBridge.hookAllMethods(cell, "setFilledNum", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    report(p);
                }
            });
            XposedBridge.log("SudokuProbe: hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("SudokuProbe: init failed: " + t);
        }
    }

    private static String dumpCells() {
        List<?> list = lastCells;
        if (list == null) return "cells: getCellDataList not called yet\n";
        StringBuilder sb = new StringBuilder("cells(row,col,answer,filled,canEdit): size=")
                .append(list.size()).append('\n');
        int n = Math.min(list.size(), MAX_CELLS);
        for (int i = 0; i < n; i++) {
            Object c = list.get(i);
            if (c == null) { sb.append("null\n"); continue; }
            try {
                sb.append(XposedHelpers.callMethod(c, "getRow")).append(',')
                  .append(XposedHelpers.callMethod(c, "getCol")).append(',')
                  .append(XposedHelpers.callMethod(c, "getAnswerNum")).append(',')
                  .append(XposedHelpers.callMethod(c, "getFilledNum")).append(',')
                  .append(XposedHelpers.callMethod(c, "isCanEdit")).append('\n');
            } catch (Throwable t) {
                sb.append("err ").append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private static void report(XC_MethodHook.MethodHookParam p) {
        final StringBuilder sb = new StringBuilder();
        sb.append("setFilledNum args=");
        for (Object a : p.args) sb.append(a).append(' ');
        sb.append('\n').append(dumpCells());
        sb.append(Log.getStackTraceString(new Throwable()));
        final String text = sb.toString();
        XposedBridge.log("SudokuProbe: " + text);

        // Latest call wins: tap one number by hand, then paste the clipboard into the chat.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context ctx = AndroidAppHelper.currentApplication();
                if (ctx == null) return;
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("sudoku-probe", text));
            } catch (Throwable ignored) { }
        });
    }
}
