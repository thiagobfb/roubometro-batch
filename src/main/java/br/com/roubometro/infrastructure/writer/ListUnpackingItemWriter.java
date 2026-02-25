package br.com.roubometro.infrastructure.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens List<List<T>> into a single List<T> and delegates to an inner writer.
 * Needed because the ItemProcessor returns List<MonthlyStat> per CSV row (1:N pivot).
 */
@Slf4j
@RequiredArgsConstructor
public class ListUnpackingItemWriter<T> implements ItemWriter<List<T>> {

    private final ItemWriter<T> delegate;

    @Override
    public void write(Chunk<? extends List<T>> chunk) throws Exception {
        List<T> flatList = new ArrayList<>();
        for (List<T> list : chunk) {
            flatList.addAll(list);
        }
        log.debug("Writing chunk: {} lists unpacked into {} items", chunk.size(), flatList.size());
        delegate.write(new Chunk<>(flatList));
        log.debug("Chunk written successfully: {} items persisted", flatList.size());
    }
}
