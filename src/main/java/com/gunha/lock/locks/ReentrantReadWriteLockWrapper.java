package com.gunha.lock.locks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * {@link ReentrantReadWriteLock}을 감싼 래퍼. 맵 하나를 보호해서, 읽기는 여럿이 동시에, 쓰기는 혼자서 한다.
 * 읽기가 많을 때의 <b>비관적 공유</b> 전략이다. 반대 전략인 낙관적 검증({@link StampedLockWrapper})과의 비교는
 * {@linkplain com.gunha.lock.locks##read-heavy locks 패키지의 비교표}에 있다.
 *
 * <h2>state 하나에 두 락을 담는다</h2>
 * AQS의 32비트 {@code state}를 반으로 나눈다. 상위 16비트는 지금 잡혀 있는 읽기 락 수(공유 모드),
 * 하위 16비트는 쓰기 락 재진입 수(배타 모드)다.
 * <pre>{@code
 * state (32 bit) = [ read holds : 16 | write holds : 16 ]
 * readLock().lock()   -> CAS: state + (1 << 16)
 * readLock().unlock() -> CAS: state - (1 << 16)
 * }</pre>
 * <ul>
 * <li><b>왜 세나</b>: 쓰기 락은 읽기 락이 하나도 없을 때만 얻을 수 있다. 쓰는 쪽은 이 수가 0인지 보고 들어갈지 정하고,
 *     마지막 독자는 놓을 때 이 수가 0이 되는 것을 보고 기다리던 쓰기 스레드를 깨운다.</li>
 * <li><b>누적이 아니다</b>: 얻을 때 올리고 놓을 때 내리므로, 읽은 총 횟수가 아니라 <b>지금 동시에</b> 쥐고 있는 수다.
 *     읽기를 아무리 많이 해도 쌓이지 않는다.</li>
 * <li><b>상한 65535</b>: 동시에 쥔 읽기 락(재진입 포함)이 16비트를 넘으면 {@code Error("Maximum lock count exceeded")}를 던진다.
 *     쓰기 재진입도 같다. 스레드 6만 개가 동시에 쥐거나 한 스레드가 6만 번 재진입해야 닿는 수라 보통은 문제가 되지 않는다.</li>
 * <li><b>스레드별 수는 따로</b>: 16비트는 모든 스레드를 합친 수라 누가 몇 번 얻었는지 모른다. 그래서 재진입과 잘못된 해제를 판단하려고
 *     스레드별 보유 수를 {@code ThreadLocal}에 따로 센다(첫 독자와 마지막 독자는 필드에 캐시해 {@code ThreadLocal} 조회를 줄인다).</li>
 * </ul>
 *
 * <h2>규칙</h2>
 * <ul>
 * <li>읽기 락: 쓰기 락이 없으면 여러 스레드가 동시에 얻는다.</li>
 * <li>쓰기 락: 읽기·쓰기 락이 하나도 없어야 얻는다(자기 쓰기 락의 재진입은 예외). 기다리는 동안 {@code WAITING}.</li>
 * <li><b>다운그레이드 가능</b>: 쓰기 락을 쥔 채 읽기 락을 얻고 쓰기 락을 놓는다
 *     ({@code writeLock().lock()} → {@code readLock().lock()} → {@code writeLock().unlock()}).</li>
 * <li><b>업그레이드 불가</b>: 읽기 락을 쥔 채 쓰기 락을 요청하면 영원히 기다린다. 두 독자가 동시에 업그레이드하면
 *     서로의 읽기 락이 풀리기를 기다리는 교착이 되기 때문에 아예 허용하지 않는다. 읽기 락을 먼저 놓고 쓰기 락을 얻어야 한다.</li>
 * <li>쓰기 기아 완화: 비공정 모드에서도 sync queue 맨 앞이 쓰기 요청이면 새 독자는 끼어들지 않고 줄을 선다.</li>
 * </ul>
 *
 * <h2>언제 이득인가 (CPU 관점)</h2>
 * 위 그림처럼 읽기 락은 얻고 놓을 때마다 같은 {@code state}에 CAS로 <b>쓴다</b>. 쓰려면 그 캐시 라인을 자기 코어로 독점해 와야 하므로,
 * 여러 코어의 독자가 동시에 읽으면 서로 기다리지는 않아도 캐시 라인이 코어 사이를 계속 오간다(캐시 라인 핑퐁,
 * <a href="https://github.com/gunhaa/java_lock/blob/main/docs/03-hardware-and-os.md#cache-coherence">docs/03 3.2절</a>).
 * <ul>
 * <li>읽기 구간이 길면(조회·계산): 이 비용은 구간 전체에 비해 작다. 독자들이 동시에 진행하는 이득이 크다.</li>
 * <li>읽기 구간이 짧으면(필드 몇 개): 읽는 시간보다 캐시 라인을 주고받는 시간이 길어져, 배타 락보다 나을 것이 별로 없다.
 *     이때는 {@code state}에 쓰지 않는 {@link StampedLockWrapper}의 낙관적 읽기가 맞다.</li>
 * </ul>
 *
 * <h2>테스트로 확인</h2>
 * 읽기 락을 쥔 스레드가 있으면 쓰기는 {@code WAITING}으로 기다린다.
 * {@snippet file="com/gunha/lock/locks/ReentrantReadWriteLockWrapperTest.java" region="writer-waits"}
 */
public class ReentrantReadWriteLockWrapper<K, V> {

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

	/** 읽기 락을 쥔 채 action을 실행한다. */
	public void runWhileReadLocked(Runnable action) {
		readLock.lock();
		try {
			action.run();
		}
		finally {
			readLock.unlock();
		}
	}

	/** {@link ReentrantReadWriteLock#getReadLockCount()}에 위임한다. 지금 잡혀 있는 읽기 락 수(모든 스레드 합계). */
	public int getReadLockCount() {
		return rwLock.getReadLockCount();
	}

}
