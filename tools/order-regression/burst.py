"""Release all prepared asynchronous attempts from one start barrier."""
import asyncio
import time


async def run_burst(attempts, send):
    ready = 0
    all_ready = asyncio.Event()
    release = asyncio.Event()
    results = [None] * len(attempts)

    async def run(index, attempt):
        nonlocal ready
        ready += 1
        if ready == len(attempts):
            all_ready.set()
        await release.wait()
        started = time.monotonic_ns()
        try:
            response = await send(attempt)
            result = {'id': attempt['id'], 'started_ns': started, 'response': response}
        except Exception as exc:
            result = {'id': attempt['id'], 'started_ns': started,
                      'error': f'{type(exc).__name__}: {exc}'}
        result['write_completed'] = attempt.get('write_completed', False)
        result['finished_ns'] = time.monotonic_ns()
        results[index] = result

    if not attempts:
        return {'barrier_arrived': 0, 'attempts': []}
    tasks = [asyncio.create_task(run(i, attempt)) for i, attempt in enumerate(attempts)]
    await all_ready.wait()
    released = time.monotonic_ns()
    release.set()
    await asyncio.gather(*tasks)
    return {'barrier_arrived': ready, 'released_ns': released, 'attempts': results}
