import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const browserPath =
  process.env.PLAYWRIGHT_EXECUTABLE_PATH ??
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

async function setup(page, { standalone = false, preview = false } = {}) {
  if (preview) {
    await page.route('**/src/survey-main.ts', async (route) => {
      const response = await route.fetch();
      await route.fulfill({
        response,
        body: (await response.text()).replace(
          'standalone: true,',
          'standalone: true, preview: true,',
        ),
      });
    });
  }
  await page.addInitScript(() => {
    window.surveyEligible = false;
    window.surveyResult = 'failure';
    window.surveyCalls = [];
    window.Native = {
      request(id, action, source) {
        const data = JSON.parse(source);
        window.surveyCalls.push({ action, data });
        let value = true;
        let error = '';
        if (action === 'account') value = { loggedIn: false };
        if (action === 'catalog') value = { data: [] };
        if (action === 'cacheList') value = { tracks: [], jobs: [], bytes: 0 };
        if (action === 'settings')
          value = { autoVip: false, preloadAudio: true };
        if (action.startsWith('update'))
          value = { configured: false, enabled: false, state: 'idle' };
        if (action === 'notificationStatus') value = { eligible: false };
        if (action === 'starStatus') value = { eligible: false };
        if (action === 'surveyStatus')
          value = {
            available: true,
            eligible:
              window.surveyEligible && !localStorage.getItem('survey-shown'),
          };
        if (action === 'surveyConsume') {
          value =
            window.surveyEligible && !localStorage.getItem('survey-shown');
          if (value) localStorage.setItem('survey-shown', 'yes');
        }
        if (action === 'surveySubmit') {
          if (window.surveyResult === 'failure')
            error = '网络连接失败，请稍后重试';
          else
            value = {
              accepted: true,
              receipt:
                window.surveyResult === 'invalid'
                  ? 'wrong-id'
                  : data.submissionId,
            };
        }
        setTimeout(() => window.nativeResolve(id, value, error), 10);
      },
    };
  });
  await page.goto(`http://127.0.0.1:5173/${standalone ? 'survey.html' : ''}`);
}

test('独立组件可预览完整问卷，不消耗邀请也不提交（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page, { standalone: true, preview: true });
    const survey = page.locator('.survey-page');
    await survey.getByText(/预览模式/).waitFor();
    await survey.getByRole('radio', { name: '3 星', exact: true }).check();
    await survey
      .getByRole('textbox', { name: '目前需要优化的地方' })
      .fill('这是本机预览内容');
    await survey.getByRole('checkbox', { name: /我已了解这是预览/ }).check();
    const email = survey.getByRole('textbox', { name: '联系邮箱' });
    for (const invalid of ['invalid', 'name@example']) {
      await email.fill(invalid);
      await email.blur();
      assert.ok(
        await survey.getByRole('button', { name: '预览完成效果' }).isDisabled(),
      );
      assert.ok(await survey.getByText('邮箱不对哦，检查一下吧！').isVisible());
    }
    await email.fill('x'.repeat(300));
    assert.equal((await email.inputValue()).length, 254);
    await email.fill('name@example.com');
    assert.ok(
      !(await survey
        .getByRole('button', { name: '预览完成效果' })
        .isDisabled()),
    );
    await email.fill('');
    await survey.getByRole('button', { name: '预览完成效果' }).click();
    await survey.getByText('感谢页预览 · 尚未提交任何内容').waitFor();
    assert.equal(
      await survey.locator('.survey-thanks svg, .survey-topbar > span').count(),
      0,
    );
    assert.equal(
      await page.evaluate(
        () =>
          window.surveyCalls.filter((call) =>
            ['surveyConsume', 'surveySubmit'].includes(call.action),
          ).length,
      ),
      0,
    );
    await page.reload();
    await survey.getByText(/预览模式/).waitFor();
  } finally {
    await browser.close();
  }
});

test('发布版隐藏问卷全部入口，播放及恢复事件不触发问卷（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    await setup(page);
    assert.equal(
      await page
        .getByRole('button', { name: '问卷预览（测试）', exact: true })
        .count(),
      0,
    );
    await page.getByRole('button', { name: '账号与设置' }).click();
    await page.evaluate(() => {
      window.surveyEligible = true;
      window.dispatchEvent(new Event('ting:star-ready'));
    });
    await page.waitForTimeout(100);
    assert.equal(await page.locator('.survey-invitation').count(), 0);
    await page
      .getByRole('button', { name: '偏好设置', exact: true })
      .last()
      .click();
    assert.equal(
      await page
        .getByRole('button', { name: '使用体验问卷', exact: true })
        .count(),
      0,
    );
    await page.evaluate(() => {
      window.dispatchEvent(new Event('ting:resume'));
      window.dispatchEvent(new Event('ting:star-ready'));
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.waitForTimeout(100);
    assert.equal(await page.locator('.survey-invitation').count(), 0);
    assert.equal(await page.locator('.survey-page').count(), 0);
    assert.equal(
      await page.evaluate(
        () =>
          window.surveyCalls.filter((call) => call.action.startsWith('survey'))
            .length,
      ),
      0,
    );
  } finally {
    await browser.close();
  }
});

test('问卷评分分支、字数、多选、失败保留与真实回执要求（原生桥替身）', async () => {
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
  });
  try {
    await mkdir('artifacts', { recursive: true });
    for (const [width, height, theme] of [
      [360, 800, 'light'],
      [390, 844, 'dark'],
      [768, 1024, 'light'],
    ]) {
      const page = await browser.newPage({ viewport: { width, height } });
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await setup(page, { standalone: true });
      await page.evaluate((value) => {
        document.documentElement.dataset.theme = value;
        window.surveyEligible = true;
        window.dispatchEvent(new Event('ting:star-ready'));
      }, theme);
      const survey = page.locator('.survey-page');
      assert.equal(await survey.locator('textarea').count(), 1);
      await survey.getByRole('radio', { name: '4 星', exact: true }).check();
      assert.equal(await survey.locator('textarea').count(), 2);
      await survey
        .getByRole('textbox', { name: '目前需要优化的地方' })
        .fill('😀'.repeat(101));
      assert.equal(
        Array.from(
          await survey
            .getByRole('textbox', { name: '目前需要优化的地方' })
            .inputValue(),
        ).length,
        100,
      );
      await survey.getByRole('checkbox', { name: /极致的轻量化/ }).check();
      await survey.getByRole('checkbox', { name: /其他 爱就要/ }).check();
      await survey
        .getByRole('textbox', { name: '还有什么让你喜欢' })
        .fill('保留这段其他评价');
      await survey
        .getByRole('textbox', { name: '其他改进建议' })
        .fill('让更多人听见好音乐');
      await survey
        .getByRole('textbox', { name: '联系邮箱' })
        .fill('survey-test@example.com');
      await survey.getByRole('checkbox', { name: /我已了解/ }).check();
      assert.ok(
        !(await survey
          .getByRole('button', { name: '提交，让 TvT 更好一点' })
          .isDisabled()),
      );
      await survey.getByRole('radio', { name: '5 星', exact: true }).check();
      assert.ok(await survey.getByText(/谢谢认可/).isVisible());
      assert.equal(
        await survey
          .getByRole('textbox', { name: '目前需要优化的地方' })
          .count(),
        0,
      );
      await survey.getByRole('checkbox', { name: /其他 爱就要/ }).uncheck();
      assert.equal(
        await survey.getByRole('textbox', { name: '还有什么让你喜欢' }).count(),
        0,
      );
      await survey
        .getByRole('button', { name: '提交，让 TvT 更好一点' })
        .click();
      await survey.getByRole('alert').waitFor();
      assert.equal(
        await survey
          .getByRole('textbox', { name: '其他改进建议' })
          .inputValue(),
        '让更多人听见好音乐',
      );
      const submissions = await page.evaluate(() =>
        window.surveyCalls.filter((call) => call.action === 'surveySubmit'),
      );
      assert.equal(submissions[0].data.optimization, '');
      assert.equal(submissions[0].data.favoriteOther, '');
      await page.evaluate(() => {
        window.surveyResult = 'invalid';
      });
      await survey
        .getByRole('button', { name: '提交，让 TvT 更好一点' })
        .click();
      await survey.getByText('服务端尚未确认保存，请稍后重试').waitFor();
      assert.equal(await survey.locator('.survey-thanks').count(), 0);
      await survey.evaluate((el) => {
        el.scrollTop = 0;
      });
      await page.screenshot({ path: `artifacts/survey-${width}-${theme}.png` });
      assert.ok(
        await survey.evaluate((el) => el.scrollWidth <= el.clientWidth),
      );
      await page.evaluate(() => {
        window.surveyResult = 'success';
      });
      await survey
        .getByRole('button', { name: '提交，让 TvT 更好一点' })
        .click();
      await survey.getByText('谢谢你，一起让 TvT 更好。').waitFor();
      const ids = await page.evaluate(() =>
        window.surveyCalls
          .filter((call) => call.action === 'surveySubmit')
          .map((call) => call.data.submissionId),
      );
      assert.equal(new Set(ids).size, 1);
      assert.deepEqual(errors, []);
      await page.close();
    }
  } finally {
    await browser.close();
  }
});
