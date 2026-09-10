import { ref, toValue, type MaybeRefOrGetter, type Ref } from 'vue';

/** Roving-tabindex keyboard navigation for a WAI-ARIA tablist: Arrow keys wrap, Home/End jump to ends. */
export function useRovingTabs<T extends string>(ids: MaybeRefOrGetter<readonly T[]>, initial: T, tabButtons: Ref<HTMLButtonElement[]>) {
  const activeTab = ref(initial) as Ref<T>;

  function onKeydown(event: KeyboardEvent, index: number): void {
    const availableIds = toValue(ids);
    let next: number;
    switch (event.key) {
      case 'ArrowRight': next = (index + 1) % availableIds.length; break;
      case 'ArrowLeft': next = (index + availableIds.length - 1) % availableIds.length; break;
      case 'Home': next = 0; break;
      case 'End': next = availableIds.length - 1; break;
      default: return;
    }
    event.preventDefault();
    activeTab.value = availableIds[next];
    tabButtons.value.find(button => button.id.endsWith(`-tab-${availableIds[next]}`))?.focus();
  }

  return { activeTab, onKeydown };
}
