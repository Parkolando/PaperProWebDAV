package com.ridi.books.viewer.main.view;
import com.ridi.books.viewer.common.library.book.download.DownloadTask;
public class BookDownloadProgressView extends android.widget.FrameLayout {
    public BookDownloadProgressView(android.content.Context context, android.util.AttributeSet attributes) { super(context, attributes); }
    public void setGrouped(boolean grouped) {}
    public void setProgress(int percent) {}
    public void setState(DownloadTask.State state) {}
}
