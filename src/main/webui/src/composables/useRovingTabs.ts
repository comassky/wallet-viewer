import { ref, type Ref } from 'vue';

/** Roving-tabindex keyboard navigation for a WAI-ARIA tablist: Arrow keys wrap, Home/End jump to ends. */
export function useRovingTabs<T extends string>(ids: readonly T[], initial: T, tabButtons: Ref<HTMLButtonElement[]>) {
  const activeTab = ref(initial) as Ref<T>;

  function onKeydown(event: KeyboardEvent, index: number): void {
    let next: number;
    switch (event.key) {
      case 'ArrowRight': next = (index + 1) % ids.length; break;
      case 'ArrowLeft': next = (index + ids.length - 1) % ids.length; break;
      case 'Home': next = 0; break;
      case 'End': next = ids.length - 1; break;
      default: return;
    }
    event.preventDefault();
    activeTab.value = ids[next];
    tabButtons.value[next]?.focus();
  }

  return { activeTab, onKeydown };
}
