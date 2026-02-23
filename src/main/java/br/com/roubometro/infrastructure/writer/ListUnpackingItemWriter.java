package br.com.roubometro.infrastructure.writer;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens List<List<T>> into a single List<T> and delegates to an inner writer.
 * Needed because the ItemProcessor returns List<MonthlyStat> per CSV row (1:N pivot).
 */
public class ListUnpackingItemWriter<T> implements ItemWriter<List<T>> {

    private final ItemWriter<T> delegate;

    public ListUnpackingItemWriter(ItemWriter<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void write(Chunk<? extends List<T>> chunk) throws Exception {
        List<T> flatList = new ArrayList<>();
        for (List<T> list : chunk) {
            flatList.addAll(list);
        }
        delegate.write(new Chunk<>(flatList));
    }
}
