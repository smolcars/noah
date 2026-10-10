import spanish from "./src/_gt/es.json";
import japanese from "./src/_gt/ja.json";

export async function loadTranslations(locale: string) {
  if (locale === "es") return spanish;
  if (locale === "ja") return japanese;
  return {};
}
