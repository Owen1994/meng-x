export const STATUS_LABELS = {
  SPROUT: '新芽',
  CONFIRMED: '已确认',
  ACCEPTED: '已采纳',
  IMPLEMENTED: '已实现',
  DECLINED: '已谢绝',
};

export const STATUS_CLASSES = {
  SPROUT: 'sprout',
  CONFIRMED: 'confirmed',
  ACCEPTED: 'accepted',
  IMPLEMENTED: 'implemented',
  DECLINED: 'declined',
};

/**
 * 功能卡展示组件（双层卡：访谈气泡与看板共用）。
 * 上层主卡四要素；下层「方案卷轴」折叠展示仙班共创的设计沉淀。
 * badge：状态徽章等顶部内容；footer：操作区（确认/状态流转/导出）。
 */
export default function FeatureCard({ card, badge, footer, className = '' }) {
  let design = null;
  try {
    design = card.designJson ? JSON.parse(card.designJson) : null;
  } catch {
    design = null;
  }
  // "待聊"或缺失的字段视为没有，全空则不渲染卷轴（许愿池炼的卡没有卷轴）
  const clean = (v) => (v && v !== '待聊' ? v : null);
  const scroll = design
    ? {
        approach: clean(design.approach),
        boundary: clean(design.boundary),
        acceptance: clean(design.acceptance),
        blueprint: clean(design.blueprint),
      }
    : null;
  const hasScroll = scroll && (scroll.approach || scroll.boundary || scroll.acceptance || scroll.blueprint);

  return (
    <div className={`feature-card ${className}`}>
      {badge && <div className="fc-badge-row">{badge}</div>}
      <div className="fc-title">
        {card.scale === '应用' && <span className="fc-app-tag">应用</span>}
        {card.title}
      </div>
      <div className="fc-stars">
        {'★'.repeat(card.creativity)}
        {'☆'.repeat(5 - card.creativity)}
      </div>
      <p className="fc-summary">{card.summary}</p>
      <dl className="fc-fields">
        <div>
          <dt>谁在用</dt>
          <dd>{card.targetUser || '—'}</dd>
        </div>
        <div>
          <dt>什么时机</dt>
          <dd>{card.triggerScene || '—'}</dd>
        </div>
        <div>
          <dt>要什么反应</dt>
          <dd>{card.reaction || '—'}</dd>
        </div>
        <div>
          <dt>边界情况</dt>
          <dd>{card.fallback || '—'}</dd>
        </div>
      </dl>
      {hasScroll && (
        <details className="fc-scroll">
          <summary>方案卷轴{card.priority ? ` · 优先级${card.priority}` : ''}</summary>
          <dl className="fc-fields">
            {scroll.approach && (
              <div>
                <dt>实现要点</dt>
                <dd>{scroll.approach}</dd>
              </div>
            )}
            {scroll.boundary && (
              <div>
                <dt>边界处理</dt>
                <dd>{scroll.boundary}</dd>
              </div>
            )}
            {scroll.acceptance && (
              <div>
                <dt>验收标准</dt>
                <dd>{scroll.acceptance}</dd>
              </div>
            )}
            {scroll.blueprint && (
              <div>
                <dt>应用蓝图</dt>
                <dd>{scroll.blueprint}</dd>
              </div>
            )}
          </dl>
        </details>
      )}
      {footer}
    </div>
  );
}
