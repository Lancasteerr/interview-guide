import { useCallback, useEffect, useRef, useState } from 'react';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import { VECTOR_STATUS_POLL_INTERVAL_MS } from '../pages/knowledgeBaseBatchUpload';

interface StatusPoll {
  controller: AbortController;
  timer?: ReturnType<typeof setTimeout>;
  failed: boolean;
}

function stopPoll(poll: StatusPoll) {
  poll.controller.abort();
  if (poll.timer) clearTimeout(poll.timer);
}

/** 每个知识库独立轮询；追加/完成文件时保留其他文件正在进行的查询。 */
export function useKnowledgeBaseVectorPolling(
  trackedIdsKey: string,
  onStatus: (knowledgeBase: KnowledgeBaseItem) => void,
) {
  const pollsRef = useRef(new Map<number, StatusPoll>());
  const pausedIdsRef = useRef(new Set<number>());
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');

  const pause = useCallback((id: number) => {
    pausedIdsRef.current.add(id);
    const poll = pollsRef.current.get(id);
    pollsRef.current.delete(id);
    if (poll) stopPoll(poll);
  }, []);

  const resume = useCallback((id: number) => {
    pausedIdsRef.current.delete(id);
    setRevision(current => current + 1);
  }, []);

  useEffect(() => {
    const polls = pollsRef.current;
    const ids = new Set(trackedIdsKey ? trackedIdsKey.split(',').map(Number) : []);
    const publishError = () => setError([...polls.values()].some(poll => poll.failed)
      ? '部分向量化状态暂时无法刷新，将自动重试'
      : '');

    for (const [id, poll] of polls) {
      if (!ids.has(id)) {
        polls.delete(id);
        stopPoll(poll);
      }
    }
    publishError();

    for (const id of ids) {
      if (polls.has(id) || pausedIdsRef.current.has(id)) continue;
      const poll: StatusPoll = { controller: new AbortController(), failed: false };
      polls.set(id, poll);

      const refresh = async () => {
        let active = true;
        try {
          const knowledgeBase = await knowledgeBaseApi.getKnowledgeBase(id, poll.controller.signal);
          // 清空列表、重试或卸载后到达的旧响应不能覆盖新的状态。
          if (polls.get(id) !== poll) return;
          poll.failed = false;
          onStatus(knowledgeBase);
          active = knowledgeBase.vectorStatus === 'PENDING' || knowledgeBase.vectorStatus === 'PROCESSING';
        } catch {
          if (polls.get(id) !== poll) return;
          poll.failed = true;
        }
        if (polls.get(id) !== poll) return;
        publishError();
        if (active) poll.timer = setTimeout(refresh, VECTOR_STATUS_POLL_INTERVAL_MS);
      };
      void refresh();
    }
  }, [trackedIdsKey, onStatus, revision]);

  useEffect(() => {
    const polls = pollsRef.current;
    const pausedIds = pausedIdsRef.current;
    return () => {
      polls.forEach(stopPoll);
      polls.clear();
      pausedIds.clear();
    };
  }, []);

  return { error, pause, resume };
}
