import { expect, test, type Page, type Route } from '@playwright/test';

const fixture = (name: string) => ({
  name,
  mimeType: 'text/plain',
  buffer: Buffer.from(`知识库测试文件 ${name}`),
});
const row = (page: Page, name: string) => page.locator('section .divide-y > div')
  .filter({ has: page.getByText(name, { exact: true }) });
const success = (route: Route, data: unknown) => route.fulfill({
  json: { code: 200, message: 'success', data },
});
const uploadResult = (id: number, vectorStatus: string, extra = {}) => ({
  knowledgeBase: { id, name: `知识库 ${id}`, vectorStatus },
  storage: {},
  duplicate: false,
  enqueueAccepted: true,
  message: '',
  ...extra,
});

test('重复文件直接显示已完成，不依赖额外状态查询', async ({ page }) => {
  let queries = 0;
  await page.route('**/api/knowledgebase/upload', route => success(route,
    uploadResult(1, 'COMPLETED', { duplicate: true })));
  await page.route('**/api/knowledgebase/1', route => {
    queries++;
    return route.abort();
  });
  await page.goto('/knowledgebase/upload');
  await page.locator('input[type=file]').setInputFiles(fixture('duplicate.txt'));
  await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
  await expect(row(page, 'duplicate.txt')).toContainText('已完成');
  await expect(row(page, 'duplicate.txt')).toContainText('已存在');
  expect(queries).toBe(0);
});

test('投递失败立即展示原因，重试只调用重新向量化', async ({ page }) => {
  let uploads = 0;
  let retries = 0;
  await page.route('**/api/knowledgebase/upload', route => {
    uploads++;
    return success(route, uploadResult(2, 'FAILED', {
      enqueueAccepted: false,
      message: '文件已保存，但任务投递失败，可在管理页重试',
    }));
  });
  await page.route('**/api/knowledgebase/2', route => success(route, {
    id: 2, vectorStatus: retries > 0 ? 'COMPLETED' : 'FAILED', vectorError: '队列不可用',
  }));
  await page.route('**/api/knowledgebase/2/revectorize', route => {
    retries++;
    return success(route, null);
  });
  await page.goto('/knowledgebase/upload');
  await page.locator('input[type=file]').setInputFiles(fixture('enqueue-failed.txt'));
  await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
  await expect(row(page, 'enqueue-failed.txt')).toContainText('文件已保存，但任务投递失败');
  await row(page, 'enqueue-failed.txt').getByTitle('重新向量化').click();
  await expect(row(page, 'enqueue-failed.txt')).toContainText('已完成');
  expect(uploads).toBe(1);
  expect(retries).toBe(1);
});

test('一个状态查询未返回时，其他文件仍独立刷新，追加文件不重启已有查询', async ({ page }) => {
  let uploads = 0;
  let slowQueries = 0;
  let releaseSlow!: () => void;
  const slowResponse = new Promise<void>(resolve => { releaseSlow = resolve; });
  await page.route('**/api/knowledgebase/upload', route => success(route,
    uploadResult(++uploads, 'PENDING')));
  await page.route('**/api/knowledgebase/1', async route => {
    slowQueries++;
    await slowResponse;
    await success(route, { id: 1, vectorStatus: 'COMPLETED' });
  });
  await page.route('**/api/knowledgebase/2', route => success(route, {
    id: 2, vectorStatus: 'COMPLETED',
  }));
  try {
    await page.goto('/knowledgebase/upload');
    await page.locator('input[type=file]').setInputFiles(fixture('slow.txt'));
    await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
    await expect.poll(() => slowQueries).toBe(1);
    await page.locator('input[type=file]').setInputFiles(fixture('fast.txt'));
    await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
    await expect(row(page, 'fast.txt')).toContainText('已完成');
    await expect(row(page, 'slow.txt')).toContainText('等待向量化');
    expect(slowQueries).toBe(1);
    releaseSlow();
    await expect(row(page, 'slow.txt')).toContainText('已完成');
  } finally {
    releaseSlow();
  }
});

test('可以用键盘打开文件选择器', async ({ page }) => {
  await page.goto('/knowledgebase/upload');
  const input = page.locator('input[type=file]');
  await input.focus();
  await expect(input).toBeFocused();
  const fileChooser = page.waitForEvent('filechooser');
  await page.keyboard.press('Enter');
  await (await fileChooser).setFiles(fixture('keyboard.txt'));
  await expect(row(page, 'keyboard.txt')).toContainText('待提交');
});

test('重新向量化时取消旧详情查询，旧失败响应不覆盖新状态', async ({ page }) => {
  let queries = 0;
  let releaseOld!: () => void;
  const oldResponse = new Promise<void>(resolve => { releaseOld = resolve; });
  await page.route('**/api/knowledgebase/upload', route => success(route,
    uploadResult(3, 'FAILED', { duplicate: true, message: '检测到重复文件，已返回现有知识库' })));
  await page.route('**/api/knowledgebase/3', async route => {
    queries++;
    if (queries === 1) {
      await oldResponse;
      return success(route, { id: 3, vectorStatus: 'FAILED', vectorError: '旧失败原因' });
    }
    return success(route, { id: 3, vectorStatus: 'COMPLETED' });
  });
  await page.route('**/api/knowledgebase/3/revectorize', route => success(route, null));
  try {
    await page.goto('/knowledgebase/upload');
    await page.locator('input[type=file]').setInputFiles(fixture('retry-vector.txt'));
    await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
    await expect.poll(() => queries).toBe(1);
    const aborted = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/knowledgebase/3'));
    await row(page, 'retry-vector.txt').getByTitle('重新向量化').click();
    await aborted;
    await expect(row(page, 'retry-vector.txt')).toContainText('已完成');
    releaseOld();
    await expect(row(page, 'retry-vector.txt')).not.toContainText('旧失败原因');
    expect(queries).toBe(2);
  } finally {
    releaseOld();
  }
});

test('状态查询短暂失败后自动恢复并清除提示', async ({ page }) => {
  let queries = 0;
  await page.route('**/api/knowledgebase/upload', route => success(route, uploadResult(4, 'PENDING')));
  await page.route('**/api/knowledgebase/4', route => {
    queries++;
    if (queries === 1) return route.fulfill({ json: { code: 500, message: '暂时不可用', data: null } });
    return success(route, { id: 4, vectorStatus: 'COMPLETED' });
  });
  await page.goto('/knowledgebase/upload');
  await page.locator('input[type=file]').setInputFiles(fixture('recover.txt'));
  await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
  const notice = page.getByText('部分向量化状态暂时无法刷新，将自动重试', { exact: true });
  await expect(notice).toBeVisible();
  await expect(row(page, 'recover.txt')).toContainText('已完成', { timeout: 10000 });
  await expect(notice).toHaveCount(0);
  expect(queries).toBe(2);
});

test('清空列表时取消在途查询，不让旧结果恢复已移除的文件', async ({ page }) => {
  let release!: () => void;
  const response = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/knowledgebase/upload', route => success(route, uploadResult(5, 'PENDING')));
  await page.route('**/api/knowledgebase/5', async route => {
    await response;
    await success(route, { id: 5, vectorStatus: 'COMPLETED' });
  });
  try {
    await page.goto('/knowledgebase/upload');
    await page.locator('input[type=file]').setInputFiles(fixture('clear.txt'));
    const started = page.waitForRequest('**/api/knowledgebase/5');
    await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
    await started;
    const aborted = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/knowledgebase/5'));
    await page.getByRole('button', { name: '清空列表' }).click();
    await aborted;
    release();
    await expect(row(page, 'clear.txt')).toHaveCount(0);
  } finally {
    release();
  }
});

test('离开上传页面取消状态查询，返回后不恢复旧批次', async ({ page }) => {
  let release!: () => void;
  const response = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/knowledgebase/list*', route => success(route, []));
  await page.route('**/api/knowledgebase/categories', route => success(route, []));
  await page.route('**/api/knowledgebase/stats', route => success(route, {
    totalCount: 0, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0,
  }));
  await page.route('**/api/knowledgebase/upload', route => success(route, uploadResult(6, 'PENDING')));
  await page.route('**/api/knowledgebase/6', async route => {
    await response;
    await success(route, { id: 6, vectorStatus: 'COMPLETED' });
  });
  try {
    await page.goto('/knowledgebase/upload');
    await page.locator('input[type=file]').setInputFiles(fixture('leave.txt'));
    const started = page.waitForRequest('**/api/knowledgebase/6');
    await page.getByRole('button', { name: '加入上传队列 1 个文件' }).click();
    await started;
    const aborted = page.waitForEvent('requestfailed', request => request.url().endsWith('/api/knowledgebase/6'));
    await page.getByRole('button', { name: '返回知识库', exact: true }).click();
    await expect(page).toHaveURL(/\/knowledgebase$/);
    await aborted;
    release();
    await page.goBack();
    await expect(page.getByRole('heading', { name: '批量上传知识库', exact: true })).toBeVisible();
    await expect(row(page, 'leave.txt')).toHaveCount(0);
  } finally {
    release();
  }
});
