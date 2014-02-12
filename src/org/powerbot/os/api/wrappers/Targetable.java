package org.powerbot.os.api.wrappers;

import java.awt.Point;

public interface Targetable {
	public Point getNextPoint();

	public boolean contains(final Point point);
}
