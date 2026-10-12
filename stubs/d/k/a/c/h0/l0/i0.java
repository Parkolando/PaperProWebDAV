package d.k.a.c.h0.l0;
import android.content.Context;
import android.widget.BaseAdapter;
import com.ridi.books.viewer.common.library.Library;
import com.ridi.books.viewer.common.library.models.Book;
import java.util.List;
public abstract class i0 extends BaseAdapter {
    public List<Book> c;
    public i0(Context context, Library library, a hint) {}
    public interface a {
        String g(); boolean h(); boolean i(); int j(); boolean k(); boolean l();
        List<Book> m(); boolean n(); boolean o(); boolean p();
    }
    public int getCount() { return 0; }
    public Book getItem(int position) { return null; }
    public long getItemId(int position) { return 0; }
    public android.view.View getView(int position, android.view.View view, android.view.ViewGroup parent) { return null; }
}
