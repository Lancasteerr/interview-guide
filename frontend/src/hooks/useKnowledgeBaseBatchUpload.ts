import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import { useKnowledgeBaseVectorPolling } from './useKnowledgeBaseVectorPolling';
import {
  canRetryUpload,
  getUploadOutcome,
  isVectorizationActive,
  MAX_CONCURRENT_UPLOADS,
  RateLimitedUploadQueue,
  selectKnowledgeBaseFiles,
  toBatchUploadStatus,
  UPLOAD_RETRY_COOLDOWN_MS,
  UPLOAD_START_INTERVAL_MS,
  type BatchUploadItem,
} from '../pages/knowledgeBaseBatchUpload';

export function useKnowledgeBaseBatchUpload() {
  const mountedRef = useRef(true);
  const itemsRef = useRef<BatchUploadItem[]>([]);
  const queueRef = useRef<RateLimitedUploadQueue | null>(null);
  const retryTimersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const revectorizingRef = useRef<number | null>(null);
  const [items, setItems] = useState<BatchUploadItem[]>([]);
  const [selectionNotice, setSelectionNotice] = useState('');
  const [revectorizingId, setRevectorizingId] = useState<number | null>(null);

  const getQueue = useCallback(() => {
    if (!queueRef.current) {
      queueRef.current = new RateLimitedUploadQueue(
        MAX_CONCURRENT_UPLOADS,
        UPLOAD_START_INTERVAL_MS,
      );
    }
    return queueRef.current;
  }, []);

  const updateItems = useCallback(
    (updater: (current: BatchUploadItem[]) => BatchUploadItem[]) => {
      const next = updater(itemsRef.current);
      itemsRef.current = next;
      if (mountedRef.current) setItems(next);
      return next;
    },
    [],
  );

  const updateItem = useCallback(
    (clientId: string, updater: (item: BatchUploadItem) => BatchUploadItem) => {
      updateItems(current => current.map(item => (
        item.clientId === clientId ? updater(item) : item
      )));
    },
    [updateItems],
  );

  const scheduleRetryUnlock = useCallback((clientId: string) => {
    const currentTimer = retryTimersRef.current.get(clientId);
    if (currentTimer) clearTimeout(currentTimer);

    const timer = setTimeout(() => {
      retryTimersRef.current.delete(clientId);
      updateItem(clientId, item => ({ ...item, retryAvailableAt: undefined }));
    }, UPLOAD_RETRY_COOLDOWN_MS);
    retryTimersRef.current.set(clientId, timer);
  }, [updateItem]);

  const uploadItem = useCallback(async (
    item: Pick<BatchUploadItem, 'clientId' | 'file' | 'customName'>,
  ) => {
    updateItem(item.clientId, current => ({
      ...current,
      status: 'UPLOADING',
      error: undefined,
      retryAvailableAt: undefined,
    }));

    try {
      const result = await knowledgeBaseApi.uploadKnowledgeBase(
        item.file,
        item.customName.trim() || undefined,
      );
      if (!mountedRef.current) return;
      updateItem(item.clientId, current => ({
        ...current,
        knowledgeBaseId: result.knowledgeBase.id,
        duplicate: result.duplicate,
        ...getUploadOutcome(result),
      }));
    } catch (error: unknown) {
      if (!mountedRef.current) return;
      updateItem(item.clientId, current => ({
        ...current,
        status: 'UPLOAD_FAILED',
        error: getErrorMessage(error) || '上传失败，请重试',
        retryAvailableAt: Date.now() + UPLOAD_RETRY_COOLDOWN_MS,
      }));
      scheduleRetryUnlock(item.clientId);
    }
  }, [scheduleRetryUnlock, updateItem]);

  const enqueueItem = useCallback((clientId: string): boolean => {
    const item = itemsRef.current.find(current => current.clientId === clientId);
    if (!item || (item.status !== 'READY' && !canRetryUpload(item))) return false;

    const accepted = getQueue().enqueue(clientId, () => uploadItem({
      clientId,
      file: item.file,
      customName: item.customName,
    }));
    if (accepted) {
      updateItem(clientId, current => ({
        ...current,
        status: 'QUEUED',
        error: undefined,
        retryAvailableAt: undefined,
      }));
    }
    return accepted;
  }, [getQueue, updateItem, uploadItem]);

  const addFiles = useCallback((fileList: FileList | File[]) => {
    const selection = selectKnowledgeBaseFiles(itemsRef.current, fileList);
    if (selection.accepted.length > 0) {
      updateItems(current => [...current, ...selection.accepted]);
    }
    setSelectionNotice(selection.rejected.join('；'));
  }, [updateItems]);

  const enqueueReadyItems = useCallback(() => {
    setSelectionNotice('');
    itemsRef.current
      .filter(item => item.status === 'READY')
      .forEach(item => enqueueItem(item.clientId));
  }, [enqueueItem]);

  const retryUpload = useCallback((clientId: string) => {
    enqueueItem(clientId);
  }, [enqueueItem]);

  const trackedIdsKey = useMemo(() => [...new Set(items
    .filter(item => item.knowledgeBaseId && (isVectorizationActive(item.status) || item.needsStatusRefresh))
    .map(item => item.knowledgeBaseId!))]
    .sort((a, b) => a - b)
    .join(','), [items]);

  const applyVectorStatus = useCallback((knowledgeBase: KnowledgeBaseItem) => {
    updateItems(current => current.map(item => {
      if (item.knowledgeBaseId !== knowledgeBase.id
        || (!isVectorizationActive(item.status) && !item.needsStatusRefresh)) return item;
      return {
        ...item,
        status: toBatchUploadStatus(knowledgeBase.vectorStatus),
        needsStatusRefresh: false,
        error: knowledgeBase.vectorStatus === 'FAILED'
          ? knowledgeBase.vectorError || '向量化失败，请重试'
          : undefined,
      };
    }));
  }, [updateItems]);
  const { error: pollError, pause: pausePolling, resume: resumePolling } =
    useKnowledgeBaseVectorPolling(trackedIdsKey, applyVectorStatus);

  const revectorize = useCallback(async (clientId: string) => {
    const item = itemsRef.current.find(current => current.clientId === clientId);
    if (!item?.knowledgeBaseId || revectorizingRef.current !== null) return;

    const knowledgeBaseId = item.knowledgeBaseId;
    pausePolling(knowledgeBaseId);
    revectorizingRef.current = knowledgeBaseId;
    setRevectorizingId(knowledgeBaseId);
    try {
      await knowledgeBaseApi.revectorize(knowledgeBaseId);
      if (!mountedRef.current) return;
      updateItems(current => current.map(currentItem => (
        currentItem.knowledgeBaseId === knowledgeBaseId
          ? { ...currentItem, status: 'PENDING', error: undefined, needsStatusRefresh: false }
          : currentItem
      )));
    } catch (error: unknown) {
      if (!mountedRef.current) return;
      updateItem(clientId, current => ({
        ...current,
        error: getErrorMessage(error) || '重新向量化失败，请重试',
      }));
    } finally {
      revectorizingRef.current = null;
      if (mountedRef.current) {
        resumePolling(knowledgeBaseId);
        setRevectorizingId(null);
      }
    }
  }, [pausePolling, resumePolling, updateItem, updateItems]);

  useEffect(() => {
    mountedRef.current = true;
    getQueue();
    return () => {
      mountedRef.current = false;
      queueRef.current?.dispose();
      queueRef.current = null;
      retryTimersRef.current.forEach(timer => clearTimeout(timer));
      retryTimersRef.current.clear();
    };
  }, [getQueue]);

  const hasUploadActivity = items.some(item => ['QUEUED', 'UPLOADING'].includes(item.status));
  const clearItems = () => {
    if (hasUploadActivity) return;
    retryTimersRef.current.forEach(timer => clearTimeout(timer));
    retryTimersRef.current.clear();
    updateItems(() => []);
    setSelectionNotice('');
  };

  return {
    items,
    hasUploadActivity,
    selectionNotice,
    pollError,
    revectorizingId,
    readyCount: items.filter(item => item.status === 'READY').length,
    completedCount: items.filter(item => item.status === 'COMPLETED').length,
    failedCount: items.filter(item => ['UPLOAD_FAILED', 'VECTOR_FAILED'].includes(item.status)).length,
    addFiles,
    clearItems,
    enqueueReadyItems,
    retryUpload,
    revectorize,
    removeItem: (clientId: string) => {
      const item = itemsRef.current.find(current => current.clientId === clientId);
      if (!item || !['READY', 'UPLOAD_FAILED'].includes(item.status)) return;
      const timer = retryTimersRef.current.get(clientId);
      if (timer) clearTimeout(timer);
      retryTimersRef.current.delete(clientId);
      updateItems(current => current.filter(currentItem => currentItem.clientId !== clientId));
    },
    updateCustomName: (clientId: string, customName: string) => updateItem(
      clientId,
      item => ['READY', 'UPLOAD_FAILED'].includes(item.status)
        ? { ...item, customName }
        : item,
    ),
  };
}
