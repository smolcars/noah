// Two readable panes, with room between them. Measure inside the safe area.
export const PANE_GAP = 24;
export const MIN_PANE_WIDTH = 260;

export function canShowCompanionPane(width: number, fontScale: number) {
  return width >= 2 * MIN_PANE_WIDTH * Math.max(1, fontScale) + PANE_GAP;
}
