package com.nous.wxnotes.receive;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\b\u0007\u0018\u0000 \n2\u00020\u0001:\u0001\nB\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\u0002\u0010\u0006J\u000e\u0010\u0007\u001a\u00020\bH\u0096@\u00a2\u0006\u0002\u0010\t\u00a8\u0006\u000b"}, d2 = {"Lcom/nous/wxnotes/receive/ImportWorker;", "Landroidx/work/CoroutineWorker;", "context", "Landroid/content/Context;", "params", "Landroidx/work/WorkerParameters;", "(Landroid/content/Context;Landroidx/work/WorkerParameters;)V", "doWork", "Landroidx/work/ListenableWorker$Result;", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "Companion", "notes_debug"})
public final class ImportWorker extends androidx.work.CoroutineWorker {
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String KEY_PATH = "batch_json_path";
    @org.jetbrains.annotations.NotNull()
    public static final com.nous.wxnotes.receive.ImportWorker.Companion Companion = null;
    
    public ImportWorker(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    androidx.work.WorkerParameters params) {
        super(null, null);
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.Nullable()
    public java.lang.Object doWork(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super androidx.work.ListenableWorker.Result> $completion) {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0012\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0005"}, d2 = {"Lcom/nous/wxnotes/receive/ImportWorker$Companion;", "", "()V", "KEY_PATH", "", "notes_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}