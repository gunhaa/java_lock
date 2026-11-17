package com.gunha.lock.locks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * {@link ReentrantReadWriteLock}으로 보호한 캐시. 읽기는 여럿이 동시에, 쓰기는 혼자서.
 *
 * <h2>state 하나에 두 락을 담는다</h2>
 * AQS의 32비트 {@code state}를 반으로 나눈다. 상위 16비트는 읽기 락 보유 수(공유 모드),
 * 하위 16비트는 쓰기 락 재진입 수(배타 모드)다. 그래서 각각 최대 65535까지다.
 * 스레드별 읽기 재진입 횟수는 {@code ThreadLocal}로 따로 센다.
 *
 * <h2>규칙</h2>
 * <ul>
 * <li>읽기 락: 쓰기 락이 없으면 여러 스레드가 동시에 얻는다.</li>
 * <li>쓰기 락: 읽기·쓰기 락이 하나도 없어야 얻는다. 기다리는 동안 {@code WAITING}.</li>
 * <li><b>다운그레이드 가능</b>: 쓰기 락을 쥔 채 읽기 락을 얻고 쓰기 락을 놓는다({@link #getOrLoad}).</li>
 * <li><b>업그레이드 불가</b>: 읽기 락을 쥔 채 쓰기 락을 요청하면 영원히 기다린다. 두 독자가 동시에 업그레이드하면
 *     서로의 읽기 락이 풀리기를 기다리는 교착이 되기 때문에 아예 허용하지 않는다.</li>
 * <li>쓰기 기아 완화: 비공정 모드에서도 대기 큐 맨 앞이 쓰기 요청이면 새 독자는 끼어들지 않고 줄을 선다.</li>
 * </ul>
 *
 * <h2>언제 이득인가 (CPU 관점)</h2>
 * 읽기 락도 결국 공유 {@code state}에 CAS로 <b>쓰는</b> 동작이다. 코어가 많고 읽기가 매우 짧으면, 모든 독자가 같은
 * 캐시 라인을 번갈아 소유하려 하는 캐시 라인 핑퐁이 생겨 기대만큼 빨라지지 않는다.
 * 읽기 구간이 충분히 길거나(조회·계산), 쓰기가 드물 때 효과가 크다.
 * 아주 짧은 읽기가 많다면 쓰기를 전혀 하지 않는 {@link StampedPoint}의 낙관적 읽기가 대안이다.
 */
public class ReadWriteCache<K, V> {

	private final Map<K, V> map = new HashMap<>();

	private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

	private final Lock readLock = rwLock.readLock();

	private final Lock writeLock = rwLock.writeLock();

	public V get(K key) {
		readLock.lock();
		try {
			return map.get(key);
		}
		finally {
			readLock.unlock();
		}
	}

	public void put(K key, V value) {
		writeLock.lock();
		try {
			map.put(key, value);
		}
		finally {
			writeLock.unlock();
		}
	}

	/**
	 * 값이 있으면 읽기 락만으로 돌려주고, 없으면 쓰기 락을 얻어 한 번만 로드한다.
	 * 로드 뒤에는 다운그레이드해서 쓰기 락 없이 읽기 락으로 결과를 읽는다.
	 */
	public V getOrLoad(K key, Function<? super K, ? extends V> loader) {
		readLock.lock();
		try {
			V cached = map.get(key);
			if (cached != null) {
				return cached;
			}
		}
		finally {
			// 업그레이드가 안 되므로 읽기 락을 먼저 놓아야 쓰기 락을 얻을 수 있다.
			readLock.unlock();
		}

		writeLock.lock();
		try {
			// 읽기 락을 놓은 사이에 다른 스레드가 먼저 로드했을 수 있으니 다시 확인한다.
			if (!map.containsKey(key)) {
				map.put(key, loader.apply(key));
			}
			// 다운그레이드: 쓰기 락을 쥔 채 읽기 락을 얻는다. 쓰기 락 소유자는 읽기 락을 얻을 수 있다.
			readLock.lock();
		}
		finally {
			writeLock.unlock();
		}

		try {
			// 다른 독자는 함께 들어올 수 있지만, 쓰기는 이 읽기 락이 풀릴 때까지 끼어들지 못한다.
			return map.get(key);
		}
		finally {
			readLock.unlock();
		}
	}

	/** 읽기 락을 쥔 채 action을 실행한다. */
	public void readWhile(Runnable action) {
		readLock.lock();
		try {
			action.run();
		}
		finally {
			readLock.unlock();
		}
	}

	/** 지금 잡혀 있는 읽기 락 수(모든 스레드 합계). */
	public int readLockCount() {
		return rwLock.getReadLockCount();
	}

	public boolean isWriteLocked() {
		return rwLock.isWriteLocked();
	}

}
