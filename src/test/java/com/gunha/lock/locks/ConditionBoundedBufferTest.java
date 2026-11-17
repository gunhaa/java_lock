package com.gunha.lock.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ConditionBoundedBufferTest {

	@Test
	@DisplayName("빈 버퍼에서 take하면 notEmpty 조건 큐에서 기다린다")
	void takerWaitsOnNotEmpty() throws Exception {
		// @start region="condition-queue"
		ConditionBoundedBuffer<String> buffer = new ConditionBoundedBuffer<>(1);
		Started<String> consumer = TestThreads.start("consumer", buffer::take);

		TestThreads.awaitState(consumer.thread(), Thread.State.WAITING);
		assertThat(buffer.hasWaitingTakers()).isTrue();
		assertThat(buffer.hasWaitingPutters()).isFalse();

		buffer.put("hello");
		assertThat(consumer.join()).isEqualTo("hello");
		assertThat(buffer.hasWaitingTakers()).isFalse();
		// @end
	}

	@Test
	@DisplayName("가득 찬 버퍼에 put하면 notFull 조건 큐에서 기다린다")
	void putterWaitsOnNotFull() throws Exception {
		ConditionBoundedBuffer<Integer> buffer = new ConditionBoundedBuffer<>(1);
		buffer.put(1);
		Started<Void> producer = TestThreads.start("producer", () -> {
			buffer.put(2);
			return null;
		});

		TestThreads.awaitState(producer.thread(), Thread.State.WAITING);
		assertThat(buffer.hasWaitingPutters()).isTrue();
		assertThat(buffer.hasWaitingTakers()).isFalse();

		assertThat(buffer.take()).isEqualTo(1);
		producer.join();
		assertThat(buffer.take()).isEqualTo(2);
	}

	@Test
	@DisplayName("signal 하나로도 생산자·소비자 여럿이 교착 없이 모든 값을 주고받는다")
	void manyProducersAndConsumers() throws Exception {
		ConditionBoundedBuffer<Integer> buffer = new ConditionBoundedBuffer<>(4);
		int producers = 4;
		int perProducer = 5_000;
		List<Started<Long>> consumers = new ArrayList<>();
		for (int i = 0; i < producers; i++) {
			int base = i * perProducer;
			TestThreads.start("producer-" + i, () -> {
				for (int n = 1; n <= perProducer; n++) {
					buffer.put(base + n);
				}
				return null;
			});
			consumers.add(TestThreads.start("consumer-" + i, () -> {
				long sum = 0;
				for (int n = 0; n < perProducer; n++) {
					sum += buffer.take();
				}
				return sum;
			}));
		}

		long total = 0;
		for (Started<Long> consumer : consumers) {
			total += consumer.join();
		}
		long count = (long) producers * perProducer;
		assertThat(total).isEqualTo(count * (count + 1) / 2);
	}

}
