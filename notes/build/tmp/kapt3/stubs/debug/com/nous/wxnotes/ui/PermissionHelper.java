package com.nous.wxnotes.ui;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001e\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0000\b\u00c7\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u0006J\u000e\u0010\u0007\u001a\u00020\b2\u0006\u0010\u0005\u001a\u00020\u0006\u00a8\u0006\t"}, d2 = {"Lcom/nous/wxnotes/ui/PermissionHelper;", "", "()V", "hasStorage", "", "context", "Landroid/content/Context;", "requestAllFiles", "", "notes_debug"})
public final class PermissionHelper {
    @org.jetbrains.annotations.NotNull()
    public static final com.nous.wxnotes.ui.PermissionHelper INSTANCE = null;
    
    private PermissionHelper() {
        super();
    }
    
    public final boolean hasStorage(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
        return false;
    }
    
    /**
     * 打开「所有文件访问权限」系统页面（引导用户手动开启）。
     */
    public final void requestAllFiles(@org.jetbrains.annotations.NotNull()
    android.content.Context context) {
    }
}