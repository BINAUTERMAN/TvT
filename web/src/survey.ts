import { reactive } from 'vue';
import { native } from './bridge';

export const survey = reactive({ pending: false, available: false });
let active = false;
let request: Promise<void> | undefined;
let refreshAgain = false;
function refresh() {
  if (!active || document.hidden || !window.Native) return;
  if (request) {
    refreshAgain = true;
    return;
  }
  request = native<{ eligible: boolean; available: boolean }>('surveyStatus')
    .then((value) => {
      if (!active) return;
      survey.pending = value?.eligible === true;
      survey.available = value?.available === true;
    })
    .catch(() => {})
    .finally(() => {
      request = undefined;
      if (refreshAgain) {
        refreshAgain = false;
        refresh();
      }
    });
}
export async function claimSurveyPrompt() {
  survey.pending = false;
  try {
    return (await native<boolean>('surveyConsume')) === true;
  } catch {
    return false;
  }
}
export function startSurvey() {
  active = true;
  window.addEventListener('ting:star-ready', refresh);
  window.addEventListener('ting:resume', refresh);
  document.addEventListener('visibilitychange', refresh);
  refresh();
}
export function stopSurvey() {
  active = false;
  window.removeEventListener('ting:star-ready', refresh);
  window.removeEventListener('ting:resume', refresh);
  document.removeEventListener('visibilitychange', refresh);
}

export const surveyChoices = [
  {
    id: 'lightweight',
    title: '极致的轻量化',
    detail: '拜托几百kb真的超棒的好吧',
  },
  {
    id: 'animation',
    title: '美观的 UI 与动画',
    detail: '赏歌悦目，舒坦 (´▽`)',
  },
  { id: 'layout', title: '简洁的界面布局', detail: '都让开，我只想好好听歌！' },
  { id: 'other', title: '其他', detail: '爱就要大声说出来 ( •̀ ω •́ )✧' },
] as const;

export interface SurveyAnswers {
  submissionId: string;
  rating: number;
  optimization: string;
  favorites: string[];
  favoriteOther: string;
  improvements: string;
  email: string;
  displayId: string;
  consent: boolean;
}
export function surveyEmailError(value: string): string {
  const email = value.trim();
  if (email.length > 254) return '邮箱最多填写 254 个字符';
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    return '邮箱不对哦，检查一下吧！';
  return '';
}

export async function sendSurvey(answers: SurveyAnswers, signal?: AbortSignal) {
  const emailError = surveyEmailError(answers.email);
  if (emailError) throw new Error(emailError);
  const payload = {
    ...answers,
    optimization: answers.rating < 5 ? answers.optimization.trim() : '',
    favoriteOther: answers.favorites.includes('other')
      ? answers.favoriteOther.trim()
      : '',
    improvements: answers.improvements.trim(),
    email: answers.email.trim(),
    displayId: answers.displayId.trim(),
  };
  let result: { accepted: boolean; receipt: string };
  if (window.Native) result = await native('surveySubmit', payload);
  else {
    const response = await fetch('/api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal,
      credentials: 'omit',
      redirect: 'error',
    });
    if (response.status === 403 || response.status === 401)
      throw new Error('问卷被权限、地区或安全验证限制，请稍后重试');
    if (response.status === 429) throw new Error('提交太频繁，请稍后再试');
    if (!response.ok)
      throw new Error(`提交失败（HTTP ${response.status}），请稍后重试`);
    try {
      result = await response.json();
    } catch {
      throw new Error('问卷服务返回了安全验证或无效响应，请稍后重试');
    }
  }
  if (result?.accepted !== true || result.receipt !== answers.submissionId)
    throw new Error('服务端尚未确认保存，请稍后重试');
}
