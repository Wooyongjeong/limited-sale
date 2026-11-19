"""Wait for the sales database clock to observe an open event."""
import asyncio
import time


async def wait_open(read, timeout=60, interval=.2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        sample = read()
        if sample['open']:
            return sample
        await asyncio.sleep(interval)
    raise TimeoutError('database did not observe the event in its sale window')
