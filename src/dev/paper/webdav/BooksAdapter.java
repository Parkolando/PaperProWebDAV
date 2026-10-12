package dev.paper.webdav;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.ridi.books.viewer.common.library.Library;
import com.ridi.books.viewer.common.library.models.Book;
import d.k.a.c.h0.l0.i0;
import java.util.Collections;
import java.util.List;

abstract class BooksAdapter extends i0 {
    BooksAdapter(Context context, Library library, List<Book> books) {
        super(context, library, new a() {
            public String g() { return ""; }
            public boolean h() { return false; }
            public boolean i() { return false; }
            public int j() { return 0; }
            public boolean k() { return false; }
            public boolean l() { return false; }
            public List<Book> m() { return Collections.emptyList(); }
            public boolean n() { return false; }
            public boolean o() { return false; }
            public boolean p() { return false; }
        });
        c = books;
    }

    /** Applies the WebDAV download state to a native book view after Ridi has bound it. */
    abstract void decorate(int position, View item);

    public View getView(int position, View view, ViewGroup parent) {
        View item = super.getView(position, view, parent);
        decorate(position, item);
        return item;
    }
}
