package com.gunha.lock.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gunha.lock.support.TestThreads;
import com.gunha.lock.support.TestThreads.Started;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MonitorBoundedBufferTest {

	@Test
	@DisplayName("빈 버퍼에서 take하면 WAITING으로 잠들고, put하면 깨어나 값을 받는다")
	void takeWaitsUntilPut() throws Exception {
		// @start region="wait-notify"
		MonitorBoundedBuffer<String> buffer = new MonitorBoundedBuffer<>(1);
		Started<String> consumer = TestThreads.start("consumer", buffer::take);

		TestThreads.awaitState(consumer.thread(), Thread.State.WAITING);
		// 소비자가 wait() 중에 모니터를 놓았기 때문에 put이 모니터에 들어갈 수 있다.
		buffer.put("hello");

		assertThat(consumer.join()).isEqualTo("hello");
		// @end
	}

	@Test
	@DisplayName("가득 찬 버퍼에 put하면 WAITING으로 잠들고, take하면 깨어난다")
	void putWaitsUntilTake() throws Exception {
		MonitorBoundedBuffer<Integer> buffer = new MonitorBoundedBuffer<>(1);
		buffer.put(1);
		Started<Void> producer = TestThreads.start("producer", () -> {
			buffer.put(2);
			return null;
		});

		TestThreads.awaitState(producer.thread(), Thread.State.WAITING);
		assertThat(buffer.take()).isEqualTo(1);

		producer.join();
		assertThat(buffer.take()).isEqualTo(2);
	}

	@Test
	@DisplayName("모니터를 갖지 않고 wait()을 부르면 IllegalMonitorStateException")
	void waitRequiresMonitor() {
		Object lock = new Object();

		assertThatThrownBy(lock::wait).isInstanceOf(IllegalMonitorStateException.class);
	}

	@Test
	@DisplayName("생산자·소비자 여럿이 동시에 써도 모든 값이 정확히 한 번씩 전달된다")
	void manyProducersAndConsumers() throws Exception {
		MonitorBoundedBuffer<Integer> buffer = new MonitorBoundedBuffer<>(4);
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
