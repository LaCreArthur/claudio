import { useCallback, useEffect, useRef, useState } from 'react';
import { REASONING_EFFORTS } from '../types';
import type { ReasoningEffort } from '../types';

interface ReasoningEffortSelectProps {
  value: ReasoningEffort;
  onChange: (effort: ReasoningEffort) => void;
}

export const ReasoningEffortSelect = ({ value, onChange }: ReasoningEffortSelectProps) => {
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const current = REASONING_EFFORTS.find(e => e.id === value) || REASONING_EFFORTS[0];

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setIsOpen(!isOpen);
  }, [isOpen]);

  const handleSelect = useCallback((effort: ReasoningEffort) => {
    onChange(effort);
    setIsOpen(false);
  }, [onChange]);

  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={`Reasoning effort: ${current.label}`}
      >
        <span className="codicon codicon-lightbulb" style={{ fontSize: '12px' }} />
        <span className="selector-button-text">{current.label}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={{ fontSize: '10px', marginLeft: '2px' }} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{
            position: 'absolute',
            bottom: '100%',
            left: 0,
            marginBottom: '4px',
            zIndex: 10000,
          }}
        >
          {REASONING_EFFORTS.map((effort) => (
            <div
              key={effort.id || '__auto__'}
              className={`selector-option ${effort.id === value ? 'selected' : ''}`}
              onClick={() => handleSelect(effort.id)}
            >
              <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
                <span>{effort.label}</span>
                <span className="model-description">{effort.description}</span>
              </div>
              {effort.id === value && (
                <span className="codicon codicon-check check-mark" />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ReasoningEffortSelect;
