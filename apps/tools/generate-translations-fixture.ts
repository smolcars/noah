// Independent notification/template oracle: use RN's actual GT resolver and bundled catalogs.
import { internalInitializeGTSRA } from '@generaltranslation/react-core/pure';
import { getMessagesInternal } from 'gt-i18n/internal';
import { msg } from 'gt-i18n';
import config from '../../client/gt.config.json';
import { loadTranslations } from '../../client/loadTranslations';
import { entries, extractRN } from './translations.mjs';

internalInitializeGTSRA({ ...config, loadTranslations });
const cases = [];
for (const locale of ['en', ...config.locales]) {
  const m = await getMessagesInternal({ locale, enableI18n: true });
  for (const [source, args] of [
    ['Cancel', {}],
    ['You received {amount}', { amount: '₿ 1,234 (≈ US$1.23)' }],
    ['{amount} to {recipient} will be sent tomorrow.', { amount: '1,234 sats', recipient: 'Alice' }],
    ['Open Noah to send {amount} to {recipient}.', { amount: '1,234 sats', recipient: 'Alice' }],
    ['{amount} to {recipient} could not be sent yet: {error}', { amount: '1,234 sats', recipient: 'Alice', error: 'offline % <test>' }],
  ] as const) {
    cases.push({ name: `${locale}: ${source}`, input: { locale, source, args: Object.values(args) }, expected: m(msg(source, args)) });
  }
}
// Plural/rich nodes are covered from the GT tree; the generator tests independently assert flattening.
const all = entries(await extractRN());
const plurals = all.filter(entry => entry.quantity).map(entry => ({ source: entry.source, translations: entry.translations }));
await Bun.write(new URL('../fixtures/translations.json', import.meta.url), JSON.stringify({ cases, plurals }, null, 2) + '\n');
