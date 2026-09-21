package ru.intelligence.heatnet.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Хранилище журнала. Фильтры собираются через Criteria API (Specification), а не через
 * «:param is null» в JPQL: PostgreSQL не может вывести тип у неиспользованного параметра.
 */
public interface EventRepository extends JpaRepository<EventEntity, String>, JpaSpecificationExecutor<EventEntity> {

    @Query("select e.type, count(e) from EventEntity e group by e.type order by count(e) desc")
    List<Object[]> countByType();
}
