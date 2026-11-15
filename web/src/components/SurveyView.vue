<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { ArrowLeft, ArrowUpRight, Heart, Star } from 'lucide-vue-next';
import { native } from '../bridge';
import {
  sendSurvey,
  surveyChoices,
  surveyEmailError,
  type SurveyAnswers,
} from '../survey';

const props = defineProps<{
  available?: boolean;
  standalone?: boolean;
  preview?: boolean;
}>();
const emit = defineEmits<{ close: [] }>();
const answers = reactive<SurveyAnswers>({
  submissionId: crypto.randomUUID(),
  rating: 0,
  optimization: '',
  favorites: [],
  favoriteOther: '',
  improvements: '',
  email: '',
  displayId: '',
  consent: false,
});
const busy = ref(false);
const sent = ref(false);
const error = ref('');
const emailTouched = ref(false);
const emailError = computed(() => surveyEmailError(answers.email));
let controller: AbortController | undefined;
let timeout: ReturnType<typeof setTimeout> | undefined;
const canSubmit = computed(
  () =>
    answers.rating > 0 &&
    answers.consent &&
    !emailError.value &&
    (props.preview || props.available !== false),
);
function textInput(
  event: Event,
  key: 'optimization' | 'favoriteOther' | 'improvements',
) {
  const input = event.target as HTMLTextAreaElement;
  const value = Array.from(input.value).slice(0, 100).join('');
  answers[key] = value;
  input.value = value;
}
async function submit() {
  if (busy.value || !canSubmit.value) return;
  if (props.preview) {
    sent.value = true;
    return;
  }
  busy.value = true;
  error.value = '';
  controller = new AbortController();
  timeout = setTimeout(() => controller?.abort(), 30000);
  try {
    await sendSurvey(answers, controller.signal);
    sent.value = true;
    answers.optimization =
      answers.favoriteOther =
      answers.improvements =
      answers.email =
      answers.displayId =
        '';
  } catch (cause) {
    error.value =
      cause instanceof Error && cause.name === 'AbortError'
        ? '请求已中断，内容仍在当前页面，可以重试'
        : cause instanceof TypeError
          ? '网络连接失败，请检查网络后重试'
          : (cause as Error).message;
  } finally {
    clearTimeout(timeout);
    busy.value = false;
  }
}
async function openRepository() {
  try {
    await native('openRepository');
  } catch (cause) {
    error.value = (cause as Error).message;
  }
}
function visibility() {
  if (document.hidden) {
    controller?.abort();
    clearTimeout(timeout);
  }
}
document.addEventListener('visibilitychange', visibility);
onBeforeUnmount(() => {
  controller?.abort();
  clearTimeout(timeout);
  document.removeEventListener('visibilitychange', visibility);
});
</script>

<template>
  <section
    class="survey-page"
    :class="{ 'survey-standalone': standalone }"
    aria-label="TvT 使用体验问卷"
  >
    <header v-if="!standalone" class="survey-topbar">
      <button class="icon round" aria-label="返回" @click="emit('close')">
        <ArrowLeft :size="22" />
      </button>
    </header>
    <div class="survey-body">
      <p v-if="preview" class="survey-preview-note" role="status">
        {{
          sent
            ? '感谢页预览 · 尚未提交任何内容'
            : '预览模式 · 可体验完整流程，填写内容不会上传'
        }}
      </p>
      <div v-if="sent" class="survey-thanks" role="status">
        <p class="survey-kicker">声音已收到</p>
        <h1>谢谢你，一起让 TvT 更好。</h1>
        <p class="muted">
          感谢你的反馈和建议，我们会尽快处理。
          <br />
          愿每一次打开，都有好音乐陪着你 (´▽`)ﾉ
        </p>
        <button v-if="!standalone" class="primary" @click="emit('close')">
          回去听歌
        </button>
      </div>
      <form v-else @submit.prevent="submit">
        <div class="survey-intro">
          <h1>TvT需要你的反馈与建议</h1>
          <p class="muted">
            关于 TvT 的喜欢、遗憾、期待，请告诉我们，帮助我们让 TvT 变得更好。
          </p>
        </div>
        <fieldset class="survey-card">
          <legend>
            <span>01</span>
            你觉得 TvT 怎么样？
            <small>必填</small>
          </legend>
          <div class="survey-stars" role="group" aria-label="为 TvT 评分">
            <label
              v-for="value in 5"
              :key="value"
              :class="{ chosen: answers.rating >= value }"
            >
              <input
                v-model="answers.rating"
                type="radio"
                name="rating"
                :value="value"
                :aria-label="`${value} 星`"
                required
              />
              <Star
                :size="28"
                :stroke-width="1.5"
                :fill="answers.rating >= value ? 'currentColor' : 'none'"
              />
            </label>
          </div>
          <p class="survey-hint" aria-live="polite">
            {{
              answers.rating === 5
                ? '谢谢认可！有空的话记得点个 Star ( •̀ ω •́ )✧'
                : answers.rating > 0
                  ? '收到！我们会继续努力，优化使用体验 (ง •̀_•́)ง'
                  : '轻点星星，留下你的评价。'
            }}
          </p>
          <button
            v-if="answers.rating === 5 && !standalone"
            class="text-button survey-repo"
            type="button"
            @click="openRepository"
          >
            去点个 Star
            <ArrowUpRight :size="15" />
          </button>
        </fieldset>
        <fieldset
          v-if="answers.rating > 0 && answers.rating < 5"
          class="survey-card"
        >
          <legend>
            <span>02</span>
            目前哪些地方需要优化？
          </legend>
          <label class="survey-field">
            <span class="sr-only">目前需要优化的地方</span>
            <textarea
              :value="answers.optimization"
              rows="2"
              placeholder="哪一次体验让你觉得，还可以更好？（选填）"
              @input="textInput($event, 'optimization')"
            />
          </label>
          <small class="survey-count">
            {{ Array.from(answers.optimization).length }}/100
          </small>
        </fieldset>
        <fieldset class="survey-card">
          <legend>
            <span>03</span>
            哪些地方让你满意？
            <small>多选 · 选填</small>
          </legend>
          <p class="survey-hint">这些地方，我们会在未来继续保留和打磨。</p>
          <label
            v-for="choice in surveyChoices"
            :key="choice.id"
            class="survey-choice"
            :class="{ selected: answers.favorites.includes(choice.id) }"
          >
            <input
              v-model="answers.favorites"
              type="checkbox"
              :value="choice.id"
            />
            <span class="survey-choice-copy">
              <strong>{{ choice.title }}</strong>
              <small>{{ choice.detail }}</small>
            </span>
          </label>
          <template v-if="answers.favorites.includes('other')">
            <label class="survey-field">
              <span>还有什么让你喜欢？</span>
              <textarea
                :value="answers.favoriteOther"
                rows="2"
                placeholder="说给我们听听吧（选填）"
                @input="textInput($event, 'favoriteOther')"
              />
            </label>
            <small class="survey-count">
              {{ Array.from(answers.favoriteOther).length }}/100
            </small>
          </template>
        </fieldset>
        <fieldset class="survey-card">
          <legend>
            <span>04</span>
            还有哪些改进建议？
          </legend>
          <p class="survey-hint">想要的新功能、界面的小问题……请畅所欲言。</p>
          <label class="survey-field">
            <span class="sr-only">其他改进建议</span>
            <textarea
              :value="answers.improvements"
              rows="2"
              placeholder="你的想法，可能成为下一个更新（选填）"
              @input="textInput($event, 'improvements')"
            />
          </label>
          <small class="survey-count">
            {{ Array.from(answers.improvements).length }}/100
          </small>
        </fieldset>
        <fieldset class="survey-card survey-contact">
          <legend>
            留个名字，保持联系
            <small>均为选填</small>
          </legend>
          <label class="survey-field">
            <span>联系邮箱</span>
            <input
              v-model="answers.email"
              type="email"
              maxlength="254"
              autocomplete="off"
              autocapitalize="none"
              :spellcheck="false"
              :aria-invalid="emailTouched && !!emailError"
              :aria-describedby="
                emailTouched && emailError ? 'survey-email-error' : undefined
              "
              @blur="emailTouched = true"
              @invalid.prevent="emailTouched = true"
            />
          </label>
          <p
            v-if="emailTouched && emailError"
            id="survey-email-error"
            class="survey-email-error"
            role="status"
          >
            {{ emailError }}
          </p>
          <label class="survey-field">
            <span>展示 ID</span>
            <input
              v-model="answers.displayId"
              maxlength="40"
              placeholder="你希望被怎样称呼？"
              autocomplete="off"
            />
          </label>
          <p class="survey-hint">
            填写展示 ID
            即表示允许我们将它列入贡献者名单；邮箱与问卷正文不会公开。
          </p>
        </fieldset>
        <div class="survey-privacy">
          <details class="survey-privacy-details">
            <summary>
              <span class="privacy-expand">查看隐私与提交说明</span>
              <span class="privacy-collapse">收起隐私与提交说明</span>
            </summary>
            <p>
              仅提交本页填写的内容，不附带音乐账号、听歌记录或设备标识。答案加密存储，保留
              180 天后按日清理；邮箱仅用于反馈回复，展示 ID
              可用于贡献者署名。服务器使用必要的网络信息进行安全防护。请勿填写密码或其他敏感信息。
            </p>
          </details>
          <label>
            <input v-model="answers.consent" type="checkbox" required />
            <span>
              {{
                preview
                  ? '我已了解这是预览，填写内容不会发送。'
                  : '我已了解以上说明，愿意提交这份反馈。'
              }}
            </span>
          </label>
        </div>
        <p
          v-if="available === false && !preview"
          class="survey-error"
          role="status"
        >
          问卷服务尚未上线，请稍后再来 (´･ω･`)
        </p>
        <p v-if="error" class="survey-error" role="alert">{{ error }}</p>
        <button
          class="primary survey-submit"
          :disabled="busy || !canSubmit"
          type="submit"
        >
          {{
            preview
              ? '预览完成效果'
              : busy
                ? '正在送达…'
                : '提交，让 TvT 更好一点'
          }}
          <Heart :size="17" />
        </button>
        <p class="survey-footnote">
          {{
            busy
              ? '请稍等，正在等待服务器确认保存。'
              : '谢谢你的时间，每一份认真都很珍贵。'
          }}
        </p>
      </form>
    </div>
  </section>
</template>

<style scoped>
.survey-page {
  position: fixed;
  inset: 0;
  z-index: 120;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--bg);
  padding-bottom: env(safe-area-inset-bottom);
}
.survey-standalone {
  position: static;
  min-height: 100dvh;
}
.survey-topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px max(18px, calc((100vw - 680px) / 2));
  border-bottom: 1px solid var(--line);
  min-height: 56px;
}
.survey-body {
  max-width: 680px;
  padding: 20px 18px 28px;
  margin: auto;
}
.survey-intro {
  margin: 0 0 22px;
}
.survey-intro > .muted {
  margin-bottom: 0;
  font-size: 14px;
  line-height: 1.65;
}
.survey-kicker {
  color: var(--muted);
  letter-spacing: 2px;
  font-size: 12px;
  margin-bottom: 12px;
}
.survey-intro h1 {
  font-size: clamp(21px, 5.5vw, 26px);
  line-height: 1.4;
  letter-spacing: -0.5px;
  margin-bottom: 8px;
}
.survey-thanks h1 {
  font-size: clamp(25px, 5vw, 32px);
  letter-spacing: -1px;
  margin-bottom: 14px;
}
.survey-card {
  min-width: 0;
  padding: 14px 14px 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  margin: 0 0 18px;
}
.survey-card legend {
  font-size: 16px;
  font-weight: 650;
  padding: 0 6px;
  max-width: 100%;
}
.survey-card legend > span {
  color: #9373c9;
  font-size: 12px;
  padding-right: 7px;
}
.survey-card legend > small {
  font-size: 11px;
  margin-left: 8px;
  font-weight: 400;
}
.survey-stars {
  display: flex;
  gap: 4px;
  margin-bottom: 8px;
}
.survey-stars label {
  position: relative;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: var(--muted);
  cursor: pointer;
}
.survey-stars input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
  width: 100%;
  height: 100%;
  margin: 0;
}
.survey-stars label:has(:focus-visible) {
  outline: 2px solid var(--accent);
  border-radius: 12px;
}
.survey-stars .chosen {
  color: #b08635;
}
.survey-hint {
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 8px;
}
.survey-stars + .survey-hint {
  min-height: 24px;
  margin-bottom: 0;
}
.survey-repo {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 10px;
}
.survey-choice {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 8px;
  margin: 2px 0;
  border-radius: 10px;
  cursor: pointer;
}
.survey-choice.selected {
  background: var(--surface);
}
.survey-choice input,
.survey-privacy input {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  accent-color: #8b6ad6;
}
.survey-choice-copy {
  flex: 1;
  min-width: 0;
}
.survey-choice strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
}
.survey-choice small {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}
.survey-field {
  display: block;
  margin-top: 8px;
}
.survey-field > span {
  display: block;
  font-size: 13px;
  margin-bottom: 6px;
}
.survey-field textarea,
.survey-field input {
  display: block;
  width: 100%;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  border-radius: 10px;
  padding: 10px;
  font: inherit;
  font-size: 14px;
  line-height: 1.5;
}
.survey-field textarea {
  resize: vertical;
  min-height: 76px;
}
.survey-field textarea:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
.survey-field :is(input, textarea)::placeholder {
  color: var(--muted);
  opacity: 1;
}
.survey-count {
  display: block;
  text-align: right;
  margin-top: 5px;
  font-size: 11px;
}
.survey-contact .survey-hint {
  margin: 10px 0 0;
}
.survey-email-error {
  margin: 6px 0 0;
  color: var(--ink);
  font-size: 12px;
  line-height: 1.6;
}
.survey-privacy {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.8;
}
.survey-privacy label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 18px 0;
}
.survey-privacy-details > summary {
  width: fit-content;
  padding: 6px 0;
  color: var(--ink);
  cursor: pointer;
  list-style: none;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.survey-privacy-details > summary::-webkit-details-marker {
  display: none;
}
.survey-privacy-details > summary:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
  border-radius: 3px;
}
.privacy-collapse,
.survey-privacy-details[open] .privacy-expand {
  display: none;
}
.survey-privacy-details[open] .privacy-collapse {
  display: inline;
}
.survey-privacy-details > p {
  margin: 4px 0 10px;
}
.survey-submit {
  width: 100%;
  min-height: 50px;
  border-radius: 999px;
  justify-content: center;
  gap: 10px;
}
.survey-footnote {
  text-align: center;
  color: var(--muted);
  font-size: 11px;
  margin: 12px 0;
}
.survey-error {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
  border-left: 3px solid #9373c9;
  padding: 9px 12px;
  background: var(--surface);
}
.survey-preview-note {
  padding: 8px 10px;
  margin-bottom: 18px;
  border-radius: 10px;
  background: var(--surface);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}
.survey-thanks {
  padding: 32px 0;
}
.survey-thanks .muted {
  font-size: 14px;
  line-height: 1.9;
  margin-bottom: 26px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 360px) {
  .survey-body {
    padding-inline: 14px;
  }
  .survey-card {
    padding-inline: 12px;
  }
  .survey-topbar {
    gap: 5px;
    padding-inline: 12px;
  }
}
</style>
