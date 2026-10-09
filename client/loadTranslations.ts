import spanish from "./src/_gt/es.json";

export async function loadTranslations(locale: string) {
  return locale === "es" ? spanish : {};
}
