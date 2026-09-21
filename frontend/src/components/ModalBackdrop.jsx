import { useEffect } from 'react';

export default function ModalBackdrop({
  onClose,
  children,
  className = 'fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4',
}) {
  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, []);

  const handleClick = (event) => {
    if (event.target === event.currentTarget) {
      onClose?.();
    }
  };

  return (
    <div className={className} onClick={handleClick}>
      {children}
    </div>
  );
}
